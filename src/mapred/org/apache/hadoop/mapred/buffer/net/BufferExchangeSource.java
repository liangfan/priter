package org.apache.hadoop.mapred.buffer.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.mapred.IFile;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapred.TaskID;
import org.apache.hadoop.mapred.buffer.OutputFile;
import org.apache.hadoop.mapred.buffer.impl.KVRecord;

public abstract class BufferExchangeSource<H extends OutputFile.Header> 
	implements Comparable<BufferExchangeSource>, BufferExchange {
	
	private static final Log LOG = LogFactory.getLog(BufferExchangeSource.class.getName());
	
	
	public static final BufferExchangeSource factory(FileSystem rfs, JobConf conf, BufferRequest request) {
		if (request.bufferType() == BufferType.FILE) {
			return new FileSource(rfs, conf, request);
		}
		if (request.bufferType() == BufferType.SNAPSHOT) {
			return new SnapshotSource(rfs, conf, request);
		}
		if (request.bufferType() == BufferType.STREAM) {
			if(conf.getBoolean("priter.job.async.time", false) || conf.getBoolean("priter.job.async.self", false)){
				return new AsyncStreamSource(rfs, conf, request);
			}else{
				return new StreamSource(rfs, conf, request);
			}
		}
		if (request.bufferType() == BufferType.PKVBUF) {
			return new PKVBufSource(rfs, conf, request, conf.getOutputValueClass());
		}
			
		return null;
	}
	
    private FileSystem rfs;
	
	/* Job configuration. */
	protected JobConf conf;
	
	/* The destination task identifier. */
	protected TaskAttemptID destination;
	
	/* The partition that we're interested in. */
	protected int partition;

	/* The address of the remote task (that made the request)
	 * receiving the outputs of each task. */
	protected InetSocketAddress address;

	/* Used to send the records. */
	protected DataOutputStream ostream = null;
	
	/* Used to receive control data. */
	protected DataInputStream istream = null;
	
	protected Socket socket = null;
	
	private boolean sentOpen = false;
	
	public boolean rollback = false;
	
	protected BufferExchangeSource(FileSystem rfs, JobConf conf, BufferRequest request) {
		this.rfs = rfs;
		this.conf = conf;
		this.destination = request.destination();
		this.partition = request.partition();
		this.address = request.destAddress();
	}
	
	@Override
	public String toString() {
		return "RequestManager destination " + destination;
	}

	@Override
	public int hashCode() {
		return this.destination.hashCode();
	}

	@Override
	public int compareTo(BufferExchangeSource o) {
		return this.destination.compareTo(o.destination) == 0 ? 
				this.partition - o.partition : this.destination.compareTo(o.destination);
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof BufferExchangeSource) {
			return this.destination.equals(((BufferExchangeSource)o).destination);
		}
		return false;
	}
	
	public TaskAttemptID destination() {
		return this.destination;
	}
	
	public final Transfer send(OutputFile file) {
		synchronized (this) {
			long sendstart = System.currentTimeMillis();
			try {
				Transfer t = transfer(file);
				long sendend = System.currentTimeMillis();
				//LOG.info("send file " + file + " use time " + (sendend-sendstart));
				
				return t;
			} catch (Exception e) {
				System.err.println("FILE " + file);
				e.printStackTrace();
				return Transfer.IGNORE;
			}		
		}
	}
	
	/*
	public final Transfer send(OutputPKVBuffer buffer) {
		synchronized (this) {
			try {
				return transfer(buffer);
			} catch (Exception e) {
				System.err.println("Buffer " + buffer);
				e.printStackTrace();
				return Transfer.IGNORE;
			}
		}
	}
	*/
	protected abstract Transfer transfer(OutputFile file);
	
	//protected abstract Transfer transfer(OutputPKVBuffer buffer);
	
	public void close() {
		synchronized (this) {
			if (socket != null && socket.isConnected()) {
				try {
					ostream.writeInt(0); // close up shop
					ostream.close();
					istream.close();
					socket.close();
				} catch (IOException e) {
					LOG.error(e);
				}
			}
			
			socket = null;
			ostream = null;
			istream = null;
		}
	}

	protected BufferExchange.Connect open(BufferExchange.BufferType bufferType) {
		if (socket == null) {
			socket = new Socket();
			try {
				socket.connect(this.address);

				ostream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
				istream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
				
				BufferExchange.Connect connection = 
					WritableUtils.readEnum(istream, BufferExchange.Connect.class);
				if (connection == BufferExchange.Connect.OPEN) {
					WritableUtils.writeEnum(ostream, bufferType);
					ostream.flush();
				}
				else {
					return connection;
				}
			} catch (IOException e) {
				if (socket != null && !socket.isClosed()) {
					try { socket.close();
					} catch (Throwable t) { }
				}
				socket = null;
				ostream = null;
				istream = null;
				return BufferExchange.Connect.ERROR;
			}
		}else if(socket.isClosed()){
			return BufferExchange.Connect.CLOSED;
		}
		return BufferExchange.Connect.OPEN;
	}
					
	protected BufferExchange.Transfer transmit(OutputFile file) {
		try {
			file.open(rfs);
		} catch (IOException e) {
			/* We don't want to send anymore of this output! */
			LOG.error("file open failed " + file);
			return BufferExchange.Transfer.TERMINATE;
		}

		try {
			ostream.writeInt(Integer.MAX_VALUE); // Sending something
			
			//LOG.info("file is " + file + " partition is " + partition);
			OutputFile.Header header = file.seek(partition);

			OutputFile.Header.writeHeader(ostream, header);
			
			//LOG.info("send header : " + header);
			
			ostream.flush();

			BufferExchange.Transfer response = WritableUtils.readEnum(istream, BufferExchange.Transfer.class);
			//LOG.info("we have feedback " + response);
			if (BufferExchange.Transfer.READY == response) {
				//LOG.info(this + " sending " + header);
				write(header, file.dataInputStream());
				
				//write the stop signal for only the priority mechanism
				if(header instanceof OutputFile.PKVBufferHeader){
					boolean stop = (file.paritions() == -100) ? true : false;
					ostream.writeBoolean(stop);
					ostream.flush();
				}
				
				return BufferExchange.Transfer.SUCCESS;
			}
			return response;
		} catch (IOException e) {
			close(); // Close so reconnect will figure out current status.
			LOG.debug(e);
		}
		return BufferExchange.Transfer.RETRY;
	}
	
	protected BufferExchange.Transfer transmit2(OutputFile file) {
		try {
			file.open(rfs);
		} catch (IOException e) {
			/* We don't want to send anymore of this output! */
			LOG.error("file open failed " + file);
			LOG.error(e);
			return BufferExchange.Transfer.TERMINATE;
		}

		try {
			ostream.writeInt(Integer.MAX_VALUE); // Sending something
			
			//LOG.info("file is " + file + " partition is " + partition);
			OutputFile.Header header = file.seek(0);

			OutputFile.Header.writeHeader(ostream, header);
			
			//LOG.info("send header : " + header);
			
			ostream.flush();

			BufferExchange.Transfer response = WritableUtils.readEnum(istream, BufferExchange.Transfer.class);
			if (BufferExchange.Transfer.READY == response) {
				//LOG.info(this + " sending " + header);
				write(header, file.dataInputStream());

				/*
				boolean stop = (file.paritions() == -100) ? true : false;
				ostream.writeBoolean(stop);
				ostream.flush();
				*/		
				file.close();
				return BufferExchange.Transfer.SUCCESS;
			}
			return response;
		} catch (IOException e) {
			close(); // Close so reconnect will figure out current status.
			LOG.debug(e);
		}
		
		try {
			file.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return BufferExchange.Transfer.RETRY;
	}
	
	
	
	/**
	 * Helper method to send records from the output file to the socket of the
	 * remote task receiving them. 
	 * Note: The current fault tolerance model does not allow us to multiplex multiple 
	 * output files. That is, we have to send in units of output files. 
	 * @throws IOException
	 */
	private void write(OutputFile.Header header, DataInputStream fstream) throws IOException {
		long length = header.compressed();
		if (length == 0 && header.progress() < 1.0f) {
			return;
		}
		
		LOG.debug("Writing data for header " + header);
		long bytesSent = 0L;
		byte[] buf = new byte[64 * 1024];
		int n = fstream.read(buf, 0, (int)Math.min(length, buf.length));
		while (n > 0) {
			bytesSent += n;
			length -= n;
			ostream.write(buf, 0, n);

			n = fstream.read(buf, 0, (int) Math.min(length, buf.length));
		}
		
		ostream.flush();
		
		LOG.debug(bytesSent + " total bytes sent for header " + header);
	}
	
	//////////////////////////////////////////////////////////////////////////////////////
	
	private static class FileSource extends BufferExchangeSource<OutputFile.FileHeader> {
		/* Store position for each source task. */
		private Map<TaskID, Integer> cursor;

		public FileSource(FileSystem rfs, JobConf conf, BufferRequest request) {
			super(rfs, conf, request);
			this.cursor = new HashMap<TaskID, Integer>();
		}

		@Override
		protected final Transfer transfer(OutputFile file) {
			OutputFile.FileHeader header = (OutputFile.FileHeader) file.header();
			TaskID taskid = header.owner().getTaskID();
			if (!cursor.containsKey(taskid) || cursor.get(taskid) == header.ids().first()) { 
				BufferExchange.Connect result = open(BufferType.FILE);
				if (result == Connect.OPEN) {
					LOG.debug("Transfer file " + file + ". Destination " + destination());
					Transfer response = transmit(file);
					if (response == Transfer.TERMINATE) {
						return Transfer.TERMINATE;
					}

					/* Update my next cursor position. */
					int position = header.ids().last() + 1;
					try { 
						int next = istream.readInt();
						if (position != next) {
							LOG.debug("Assumed next position " + position + " != actual " + next);
							position = next;
						}
					} catch (IOException e) { e.printStackTrace(); LOG.error(e); }

					if (response == Transfer.SUCCESS) {
						if (header.eof()) {
							LOG.debug("Transfer end of file for source task " + taskid);
							close();
						}
						cursor.put(taskid, position);
						LOG.debug("Transfer complete. New position " + cursor.get(taskid) + ". Destination " + destination());
					}
					else if (response == Transfer.IGNORE){
						cursor.put(taskid, position); // Update my cursor position
					}
					else {
						LOG.debug("Unsuccessful send. Transfer response: " + response);
					}

					return response;
				}
				else if (result == Connect.BUFFER_COMPLETE) {
					cursor.put(taskid, Integer.MAX_VALUE);
					return Transfer.SUCCESS;
				}
				else {
					return Transfer.RETRY;
				}
			}
			else {
				LOG.debug("Transfer ignore header " + header + " current position " + cursor.get(taskid));
				return Transfer.IGNORE;
			}
		}

	}
	
	public static class StreamSource extends BufferExchangeSource<OutputFile.StreamHeader> {
		public Map<TaskID, Long> cursor;
		
		public StreamSource(FileSystem rfs, JobConf conf, BufferRequest request) {
			super(rfs, conf, request);
			this.cursor = new HashMap<TaskID, Long>();
		}
		
		@Override
		protected final Transfer transfer(OutputFile file) {
			OutputFile.StreamHeader header = (OutputFile.StreamHeader) file.header();
			TaskID taskid = header.owner().getTaskID();
			//LOG.info("cursor " + cursor.get(taskid) + " header " + header.sequence());
			if (!cursor.containsKey(taskid) || cursor.get(taskid) == header.sequence() || (rollback && cursor.get(taskid) > header.sequence())) { 
				//LOG.info("before send");
				BufferExchange.Connect result = open(BufferType.STREAM);
				//LOG.info("after send");
				
				if (result == Connect.OPEN) {
					//LOG.info("Transfer stream file " + file + ". Destination " + destination());
					Transfer response = transmit(file);

					if (response == Transfer.TERMINATE) {
						LOG.info("terminate");
						return Transfer.TERMINATE;
					}

					/* Update my next cursor position. */
					long position = header.sequence() + 1;
					
					if (response == Transfer.IGNORE){
						LOG.info("ignore");
						cursor.put(taskid, position); // Update my cursor position
						return response;
					}
					
					try { 
						long next = istream.readLong();
						if (position != next) {
							position = next;
						}
					} catch (IOException e) { e.printStackTrace(); LOG.error(e); }

					if (response == Transfer.SUCCESS) {
						cursor.put(taskid, position);
						//LOG.info("Transfer complete. New position " + cursor.get(taskid) + ". Destination " + destination());
					}
					else if (response == Transfer.IGNORE){
						LOG.info("ignore");
						cursor.put(taskid, position); // Update my cursor position
						return response;
					}
					else {
						LOG.info("Unsuccessful send. Transfer response: " + response);
					}

					if(rollback && cursor.get(taskid) > header.sequence()) rollback = false;
					return response;
				}else if(result == Connect.CLOSED){
					return Transfer.CLOSED;
				}
				else {
					return Transfer.RETRY;
				}
			}
			else {
				LOG.info("Stream transfer ignore " + header + 
						" current sequence " + cursor.get(taskid));
				return Transfer.IGNORE;
			}
		}

	}
	
	private static class AsyncStreamSource extends BufferExchangeSource<OutputFile.StreamHeader> {
		private Map<TaskID, Long> cursor;
		
		public AsyncStreamSource(FileSystem rfs, JobConf conf, BufferRequest request) {
			super(rfs, conf, request);
			this.cursor = new HashMap<TaskID, Long>();
		}
		
		@Override
		protected final Transfer transfer(OutputFile file) {
			OutputFile.StreamHeader header = (OutputFile.StreamHeader) file.header();
			TaskID taskid = header.owner().getTaskID();
			if (!cursor.containsKey(taskid) || cursor.get(taskid) == header.sequence()) { 
				//LOG.info("before send");
				BufferExchange.Connect result = open(BufferType.STREAM);
				//LOG.info("after send");
				
				if (result == Connect.OPEN) {
					LOG.info("Transfer stream file " + file + ". Destination " + destination());
					Transfer response = transmit(file);
					if (response == Transfer.TERMINATE) {
						return Transfer.TERMINATE;
					}

					/* Update my next cursor position. */
					long position = header.sequence() + 1;
					try { 
						istream.readLong();
					} catch (IOException e) { e.printStackTrace(); LOG.error(e); }

					if (response == Transfer.SUCCESS) {
						cursor.put(taskid, position);
						LOG.debug("Transfer complete. New position " + cursor.get(taskid) + ". Destination " + destination());
					}
					else if (response == Transfer.IGNORE){
						cursor.put(taskid, position); // Update my cursor position
					}
					else {
						LOG.debug("Unsuccessful send. Transfer response: " + response);
					}

					return response;
				}
				else {
					return Transfer.RETRY;
				}
			}
			else {
				LOG.info("Stream transfer ignore " + header + 
						" current sequence " + cursor.get(taskid));
				return Transfer.IGNORE;
			}
		}

	}
	
	private static class SnapshotSource extends BufferExchangeSource<OutputFile.SnapshotHeader> {
		private Map<TaskID, Float> cursor;
		
		public SnapshotSource(FileSystem rfs, JobConf conf, BufferRequest request) {
			super(rfs, conf, request);
			this.cursor = new HashMap<TaskID, Float>();
		}

		@Override
		protected final Transfer transfer(OutputFile file) {
			OutputFile.SnapshotHeader header = (OutputFile.SnapshotHeader) file.header();
			TaskID taskid = header.owner().getTaskID();
			if (!cursor.containsKey(taskid) || cursor.get(taskid) < header.progress()) {
				BufferExchange.Connect result = open(BufferExchange.BufferType.SNAPSHOT);
				if (result == BufferExchange.Connect.OPEN) {
					Transfer response = transmit(file);
					if (response == Transfer.TERMINATE) {
						return Transfer.TERMINATE;
					}
					
					try {
						float pos = istream.readFloat();
						cursor.put(taskid, pos);
						if (header.eof()) {
							close();
						}
					} catch (IOException e) {
						e.printStackTrace();
						LOG.error(e);
						return Transfer.RETRY;
					}
					return response;
				}
				else if (result == BufferExchange.Connect.BUFFER_COMPLETE) {
					cursor.put(taskid, 1f);
					close();
					return Transfer.SUCCESS;
				} 
				else {
					return Transfer.RETRY;
				}
			}
			return Transfer.IGNORE;
		}

	}

	private static class PKVBufSource<V extends Object> extends BufferExchangeSource<OutputFile.SnapshotHeader> {
		
		private Map<TaskID, Long> cursor;
		private Class<V> valClass;
		
		public PKVBufSource(FileSystem rfs, JobConf conf, BufferRequest request, Class<V> valClass) {
			super(rfs, conf, request);
			this.cursor = new HashMap<TaskID, Long>();
			this.valClass = valClass;
		}

		//transfer in-memory record to map
		protected BufferExchange.Transfer transmit3(OutputFile file) {

			try {
				ostream.writeInt(Integer.MAX_VALUE); // Sending something
				
				//LOG.info("file is " + file + " partition is " + partition);
				OutputFile.Header header = file.seek2();

				OutputFile.Header.writeHeader(ostream, header);
				
				//LOG.info("send header : " + header);
				
				ostream.flush();

				BufferExchange.Transfer response = WritableUtils.readEnum(istream, BufferExchange.Transfer.class);
				if (BufferExchange.Transfer.READY == response) {
					
					IFile.SocketWriter<IntWritable, V> writer = new IFile.SocketWriter(conf, ostream, IntWritable.class, valClass, null, null);
					LOG.info("sending kv from memory " + file.outputMemQueue.size());
					synchronized(file.outputMemQueue){
						for(Object o : file.outputMemQueue){
							KVRecord<IntWritable, V> record = (KVRecord<IntWritable, V>)o;
							LOG.info("write " + record);
							writer.append(record.k, record.v);
						}
						
						writer.close();
						//ostream.flush();
						file.outputMemQueue.clear();
					}

					return BufferExchange.Transfer.SUCCESS;
				}
				return response;
			} catch (IOException e) {
				close(); // Close so reconnect will figure out current status.
				LOG.debug(e);
			}
			
			try {
				file.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return BufferExchange.Transfer.RETRY;
		}
		
		@Override
		protected final Transfer transfer(OutputFile file) {
			OutputFile.PKVBufferHeader header = (OutputFile.PKVBufferHeader) file.header();
			TaskID taskid = header.owner().getTaskID();
			//LOG.info("cursor : " + cursor.containsKey(taskid) + "\t" + cursor.get(taskid) + "\t" + header.iteration());
			
			if (!cursor.containsKey(taskid) || cursor.get(taskid) == header.iteration() || rollback) {
				BufferExchange.Connect result = open(BufferType.PKVBUF);
				if (result == Connect.OPEN) {
					//LOG.info("Transfer pkvbuffer file " + file + ". Destination " + destination());
					Transfer response = null;
					if(conf.getBoolean("priter.transfer.mem", false)){
						response = transmit3(file);
					}else{
						response = transmit2(file);
					}
					
					if (response == Transfer.TERMINATE) {
						return Transfer.TERMINATE;
					}

					/* Update my next cursor position. */
					long position = header.iteration() + 1;
					try { 
						long next = istream.readLong();
						
						if (position != next) {
							position = next;
						}
					} catch (IOException e) { e.printStackTrace(); LOG.error(e); }

					if (response == Transfer.SUCCESS) {
						cursor.put(taskid, position);
						LOG.debug("Transfer complete. New position " + cursor.get(taskid) + ". Destination " + destination());
					}
					else if (response == Transfer.IGNORE){
						cursor.put(taskid, position); // Update my cursor position
					}
					else {
						LOG.debug("Unsuccessful send. Transfer response: " + response);
					}

					if(rollback) rollback = false;
					return response;
				}
				else {
					return Transfer.RETRY;
				}
			}
			else {
				LOG.debug("Stream transfer ignore " + header + 
						" current sequence " + cursor.get(taskid));
				return Transfer.IGNORE;
			}	
		}

	}
}
