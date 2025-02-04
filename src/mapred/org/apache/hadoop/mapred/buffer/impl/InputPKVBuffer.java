package org.apache.hadoop.mapred.buffer.impl;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.Queue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.mapred.IFile;
import org.apache.hadoop.mapred.InputCollector;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.Task;
import org.apache.hadoop.mapred.buffer.BufferUmbilicalProtocol;
import org.apache.hadoop.mapred.buffer.OutputFile;
import org.apache.hadoop.util.Progress;

public class InputPKVBuffer<K extends Object, V extends Object> implements
		InputCollector<K, V> {

	private static final Log LOG = LogFactory.getLog(InputPKVBuffer.class.getName());
	
	public int iteration = 0;
	private FileSystem hdfs;
	private K savedKey;
	private V savedValue;			//for get K, V pair
	private OutputFile.Header savedHeader = null;	
	private Class<K> keyClass;
	private Class<V> valClass;
	private Deserializer keyDeserializer;	
	private Deserializer valDeserializer;	
	private JobConf job;
	private Queue<KVRecord<K, V>> recordsQueue = null;
	public String exeQueueFile;

	public InputPKVBuffer(BufferUmbilicalProtocol umbilical, Task task, JobConf job, 
			Reporter reporter, Progress progress, Class<K> keyClass, Class<V> valClass) throws IOException{	
		
		LOG.info("InputPKVBuffer is created for task " + task.getTaskID());
		this.iteration = 0;
		
		this.job = job;
		this.hdfs = FileSystem.get(job);
		this.keyClass = keyClass;
		this.valClass = valClass;
		SerializationFactory serializationFactory = new SerializationFactory(job);
	    this.keyDeserializer = serializationFactory.getDeserializer(IntWritable.class);
	    this.valDeserializer = serializationFactory.getDeserializer(valClass);
	    
	    this.recordsQueue = new LinkedList<KVRecord<K, V>>();
	    this.exeQueueFile = job.get("mapred.output.dir") + "/_ExeQueueTemp/" + 
	    					task.getTaskID().getTaskID().getId() + "-exequeue";
	}
	
	@Override
	public void close() {
		// TODO Auto-generated method stub

	}

	@Override
	public void flush() throws IOException {
		// TODO Auto-generated method stub

	}
	
	@Override
	public void free() {
		this.recordsQueue.clear();
		this.savedKey = null;
		this.savedValue = null;
	}

	//should be called in user-defined IterativeMapper.initPKVBuffer()
	public synchronized void init(K key, V value) throws IOException {
		synchronized(this.recordsQueue){
			KVRecord<K, V> rec = new KVRecord<K, V>(key, value);
			this.recordsQueue.add(rec);
		}
	}
	
	//load execution queue, for load balancing and fault tolerance, maybe not useful
	public synchronized int loadExeQueue() throws IOException{
		FSDataInputStream istream = hdfs.open(new Path(exeQueueFile));
		BufferedReader reader = new BufferedReader(new InputStreamReader(istream));
		
		int count = 0;
		while(reader.ready()){
			String line = reader.readLine();
			String[] field = line.split("\t", 4);
			
			Writable key;
			if(keyClass == IntWritable.class){
				key = new IntWritable(Integer.parseInt(field[1]));
			}else if(valClass == DoubleWritable.class){
				key = new DoubleWritable(Double.parseDouble((field[1])));
			}else if(valClass == FloatWritable.class){
				key = new FloatWritable(Float.parseFloat((field[1])));
			}else{
				throw new IOException(keyClass + " not type matched for key class");
			}

			if(valClass == IntWritable.class){
				IntWritable val = new IntWritable(Integer.parseInt(field[1]));
				this.recordsQueue.add(new KVRecord(key, val));
			}else if(valClass == DoubleWritable.class){
				DoubleWritable val = new DoubleWritable(Double.parseDouble((field[1])));
				this.recordsQueue.add(new KVRecord(key, val));
			}else if(valClass == FloatWritable.class){
				FloatWritable val = new FloatWritable(Float.parseFloat((field[1])));
				this.recordsQueue.add(new KVRecord(key, val));
			}else{
				throw new IOException(valClass + " not type matched for value class");
			}
			count++;
		}
		
		reader.close();		
		return count;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public synchronized boolean read(DataInputStream istream, OutputFile.Header header)
			throws IOException {	
		if(this.iteration <= ((OutputFile.PKVBufferHeader)header).iteration()){
			synchronized(this.recordsQueue){
				long start = System.currentTimeMillis();
				this.iteration = ((OutputFile.PKVBufferHeader)header).iteration();
				
				IFile.Reader reader = new IFile.Reader(job, istream, header.compressed(), null, null);
				DataInputBuffer key = new DataInputBuffer();
				DataInputBuffer value = new DataInputBuffer();
				
				while (reader.next(key, value)) {
					keyDeserializer.open(key);
					valDeserializer.open(value);
					Object keyObject = null;
					Object valObject = null;
					keyObject = keyDeserializer.deserialize(keyObject);
					valObject = valDeserializer.deserialize(valObject);

					this.recordsQueue.add(new KVRecord<K, V>((K)keyObject, (V)valObject));
				}
				
				long end = System.currentTimeMillis();
				LOG.info("map read use time " + (end-start));
				this.notifyAll();			//notify MapTask's pkvBuffer.wait()				
				return true;
			}
		}
		
		return false;
	}

	public OutputFile.Header getRecentHeader() {
		return this.savedHeader;
	}

	public synchronized boolean next() {
		//LOG.info("doing next, for map . size is " + this.recordsQueue.size());
		synchronized(this.recordsQueue){
			KVRecord<K, V> record = this.recordsQueue.poll();
			if(record == null){
				this.recordsQueue.clear();
				this.recordsQueue.notifyAll();
				return false;
			}
			else{
				this.savedKey = record.k;
				this.savedValue = record.v;
				return true;
			}
		}
	}
	
	
	public K getTopKey() {
		return this.savedKey;
	}
	
	public V getTopValue() {
		return this.savedValue;
	}

	@Override
	public ValuesIterator<K, V> valuesIterator() throws IOException {
		return null;
	}
}
