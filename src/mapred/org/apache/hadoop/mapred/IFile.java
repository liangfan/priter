/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;

/**
 * <code>IFile</code> is the simple <key-len, value-len, key, value> format
 * for the intermediate map-outputs in Map-Reduce.
 * 
 * There is a <code>Writer</code> to write out map-outputs in this format and 
 * a <code>Reader</code> to read files of this format.
 */
public class IFile {

  private static final int EOF_MARKER = -1;
  
  private static final Log LOG = LogFactory.getLog(IFile.class.getName());
  
  public static class Writer<K extends Object, V extends Object> {
	    FSDataOutputStream out;
	    boolean ownOutputStream = false;
	    long start = 0;
	    FSDataOutputStream rawOut;
	    
	    CompressionOutputStream compressedOut;
	    Compressor compressor;
	    boolean compressOutput = false;
	    
	    long decompressedBytesWritten = 0;
	    long compressedBytesWritten = 0;

	    // Count records written to disk
	    private long numRecordsWritten = 0;
	    private final Counters.Counter writtenRecordsCounter;

	    IFileOutputStream checksumOut;

	    Class<K> keyClass;
	    Class<V> valueClass;
	    Serializer<K> keySerializer;
	    Serializer<V> valueSerializer;
	    
	    DataOutputBuffer buffer = new DataOutputBuffer();

	    public Writer(Configuration conf, FileSystem fs, Path file, 
	                  Class<K> keyClass, Class<V> valueClass,
	                  CompressionCodec codec,
	                  Counters.Counter writesCounter) throws IOException {
	      this(conf, fs.create(file), keyClass, valueClass, codec,
	           writesCounter);
	      ownOutputStream = true;
	    }
	    
	    public Writer(Configuration conf, FSDataOutputStream out, 
	        Class<K> keyClass, Class<V> valueClass,
	        CompressionCodec codec, Counters.Counter writesCounter)
	        throws IOException {
	      this.writtenRecordsCounter = writesCounter;
	      this.checksumOut = new IFileOutputStream(out);
	      this.rawOut = out;
	      this.start = this.rawOut.getPos();
	      
	      if (codec != null) {
	        this.compressor = CodecPool.getCompressor(codec);
	        this.compressor.reset();
	        this.compressedOut = codec.createOutputStream(checksumOut, compressor);
	        this.out = new FSDataOutputStream(this.compressedOut,  null);
	        this.compressOutput = true;
	      } else {
	        this.out = new FSDataOutputStream(checksumOut,null);
	      }
	      
	      this.keyClass = keyClass;
	      this.valueClass = valueClass;
	      SerializationFactory serializationFactory = new SerializationFactory(conf);
	      this.keySerializer = serializationFactory.getSerializer(keyClass);
	      this.keySerializer.open(buffer);
	      this.valueSerializer = serializationFactory.getSerializer(valueClass);
	      this.valueSerializer.open(buffer);
	    }
	    
	    public void close() throws IOException {

            keySerializer.close();
            valueSerializer.close();

            WritableUtils.writeVInt(out, EOF_MARKER);
            WritableUtils.writeVInt(out, EOF_MARKER);
            decompressedBytesWritten += 2 * WritableUtils.getVIntSize(EOF_MARKER);	
	      
	      //Flush the stream
	      out.flush();
	  
	      if (compressOutput) {
	        // Flush
	        compressedOut.finish();
	        compressedOut.resetState();
	      }
	      
	      // Close the underlying stream iff we own it...
	      if (ownOutputStream) {
	        out.close();
	      }
	      else {
	        // Write the checksum
	        checksumOut.finish();
	      }

	      compressedBytesWritten = rawOut.getPos() - start;

	      if (compressOutput) {
	        // Return back the compressor
	        CodecPool.returnCompressor(compressor);
	        compressor = null;
	      }

	      out = null;
	      if(writtenRecordsCounter != null) {
	        writtenRecordsCounter.increment(numRecordsWritten);
	      }
	    }

	    public void append(K key, V value) throws IOException {
	      if (key.getClass() != keyClass)
	        throw new IOException("wrong key class: "+ key.getClass()
	                              +" is not "+ keyClass);
	      if (value.getClass() != valueClass)
	        throw new IOException("wrong value class: "+ value.getClass()
	                              +" is not "+ valueClass);

	      // Append the 'key'
	      keySerializer.serialize(key);
	      int keyLength = buffer.getLength();
	      if (keyLength < 0) {
	        throw new IOException("Negative key-length not allowed: " + keyLength + 
	                              " for " + key);
	      }

	      // Append the 'value'
	      valueSerializer.serialize(value);
	      int valueLength = buffer.getLength() - keyLength;
	      if (valueLength < 0) {
	        throw new IOException("Negative value-length not allowed: " + 
	                              valueLength + " for " + value);
	      }
	      
	      //test
	      //LOG.info("key length " + keyLength + " value length " + valueLength);
	      
	      // Write the record out
	      WritableUtils.writeVInt(out, keyLength);                  // key length
	      WritableUtils.writeVInt(out, valueLength);                // value length
	      out.write(buffer.getData(), 0, buffer.getLength());       // data
	      out.flush();
	      // Reset
	      buffer.reset();
	      
	      // Update bytes written
	      decompressedBytesWritten += keyLength + valueLength + 
	                                  WritableUtils.getVIntSize(keyLength) + 
	                                  WritableUtils.getVIntSize(valueLength);
	      ++numRecordsWritten;
	    }
	    
	    public void append(DataInputBuffer key, DataInputBuffer value)
	    throws IOException {
	      int keyLength = key.getLength() - key.getPosition();
	      if (keyLength < 0) {
	        throw new IOException("Negative key-length not allowed: " + keyLength + 
	                              " for " + key);
	      }
	      
	      int valueLength = value.getLength() - value.getPosition();
	      if (valueLength < 0) {
	        throw new IOException("Negative value-length not allowed: " + 
	                              valueLength + " for " + value);
	      }

	      WritableUtils.writeVInt(out, keyLength);
	      WritableUtils.writeVInt(out, valueLength);
	      out.write(key.getData(), key.getPosition(), keyLength); 
	      out.write(value.getData(), value.getPosition(), valueLength); 

	      // Update bytes written
	      decompressedBytesWritten += keyLength + valueLength + 
	                      WritableUtils.getVIntSize(keyLength) + 
	                      WritableUtils.getVIntSize(valueLength);
	      ++numRecordsWritten;
	    }
	    
	    
	    public long getRawLength() {
	      return decompressedBytesWritten;
	    }
	    
	    public long getCompressedLength() {
	      return compressedBytesWritten;
	    }
	  }
  
  
  public static class SocketWriter<K extends Object, V extends Object> {
	    DataOutputStream out;
	    boolean ownOutputStream = false;
	    long start = 0;
	    DataOutputStream rawOut;
	    
	    long decompressedBytesWritten = 0;
	    long compressedBytesWritten = 0;

	    // Count records written to disk
	    private long numRecordsWritten = 0;
	    private final Counters.Counter writtenRecordsCounter;

	    Class<K> keyClass;
	    Class<V> valueClass;
	    Serializer<K> keySerializer;
	    Serializer<V> valueSerializer;
	    
	    private boolean bLenWritten = false;
	    
	    DataOutputBuffer buffer = new DataOutputBuffer();

	    public SocketWriter(Configuration conf, DataOutputStream out, 
	        Class<K> keyClass, Class<V> valueClass,
	        CompressionCodec codec, Counters.Counter writesCounter)
	        throws IOException {
	      this.writtenRecordsCounter = writesCounter;
	      this.out = out;
	      
	      this.keyClass = keyClass;
	      this.valueClass = valueClass;
	      SerializationFactory serializationFactory = new SerializationFactory(conf);
	      this.keySerializer = serializationFactory.getSerializer(keyClass);
	      this.keySerializer.open(buffer);
	      this.valueSerializer = serializationFactory.getSerializer(valueClass);
	      this.valueSerializer.open(buffer);
	    }
	    
	    public void close() throws IOException {

          keySerializer.close();
          valueSerializer.close();

          WritableUtils.writeVInt(out, EOF_MARKER);
          WritableUtils.writeVInt(out, EOF_MARKER);
          decompressedBytesWritten += 2 * WritableUtils.getVIntSize(EOF_MARKER);	
	      
	      //Flush the stream
	      out.flush();
	      
	      out = null;
	      if(writtenRecordsCounter != null) {
	        writtenRecordsCounter.increment(numRecordsWritten);
	      }
	    }

	    public void append(K key, V value) throws IOException {
	      if (key.getClass() != keyClass)
		        throw new IOException("wrong key class: "+ key.getClass()
		                              +" is not "+ keyClass);
		      if (value.getClass() != valueClass)
		        throw new IOException("wrong value class: "+ value.getClass()
		                              +" is not "+ valueClass);

		      // Append the 'key'
		      keySerializer.serialize(key);
		      int keyLength = buffer.getLength();
		      if (keyLength < 0) {
		        throw new IOException("Negative key-length not allowed: " + keyLength + 
		                              " for " + key);
		      }

		      // Append the 'value'
		      valueSerializer.serialize(value);
		      int valueLength = buffer.getLength() - keyLength;
		      if (valueLength < 0) {
		        throw new IOException("Negative value-length not allowed: " + 
		                              valueLength + " for " + value);
		      }
		      
		      //test
		      //LOG.info("key length " + keyLength + " value length " + valueLength);
		      
		      // Write the record out
		      WritableUtils.writeVInt(out, keyLength);                  // key length
		      WritableUtils.writeVInt(out, valueLength);                // value length
		      out.write(buffer.getData(), 0, buffer.getLength());       // data
		      //out.flush();
		      // Reset
		      buffer.reset();
		      
		      // Update bytes written
		      decompressedBytesWritten += keyLength + valueLength + 
		                                  WritableUtils.getVIntSize(keyLength) + 
		                                  WritableUtils.getVIntSize(valueLength);
		      ++numRecordsWritten;
	    }	    
	    
	    public long getRawLength() {
	      return decompressedBytesWritten;
	    }
	  }
  /**
   * <code>IFile.Writer</code> to write out intermediate map-outputs. 
   */
  public static class PriorityWriter<P extends Object, K extends Object, V extends Object> {
    FSDataOutputStream out;
    boolean ownOutputStream = false;
    long start = 0;
    FSDataOutputStream rawOut;
    
    CompressionOutputStream compressedOut;
    Compressor compressor;
    boolean compressOutput = false;
    
    long decompressedBytesWritten = 0;
    long compressedBytesWritten = 0;

    // Count records written to disk
    private long numRecordsWritten = 0;
    private final Counters.Counter writtenRecordsCounter;

    IFileOutputStream checksumOut;

    Class<P> priorityClass;
    Class<K> keyClass;
    Class<V> valueClass;
    Serializer<P> prioritySerializer;
    Serializer<K> keySerializer;
    Serializer<V> valueSerializer;
    
    DataOutputBuffer buffer = new DataOutputBuffer();
    
    public PriorityWriter(Configuration conf, FileSystem fs, Path file, 
    		Class<P> priorityClass,
            Class<K> keyClass, Class<V> valueClass,
            CompressionCodec codec,
            Counters.Counter writesCounter) throws IOException {
		this(conf, fs.create(file), priorityClass, keyClass, valueClass, codec,
		     writesCounter);
		ownOutputStream = true;
	}
    
    
    public PriorityWriter(Configuration conf, FSDataOutputStream out, 
    		Class<P> priorityClass,
            Class<K> keyClass, Class<V> valueClass,
            CompressionCodec codec, Counters.Counter writesCounter)
            throws IOException {
          this.writtenRecordsCounter = writesCounter;
          this.checksumOut = new IFileOutputStream(out);
          this.rawOut = out;
          this.start = this.rawOut.getPos();
          
          if (codec != null) {
            this.compressor = CodecPool.getCompressor(codec);
            this.compressor.reset();
            this.compressedOut = codec.createOutputStream(checksumOut, compressor);
            this.out = new FSDataOutputStream(this.compressedOut,  null);
            this.compressOutput = true;
          } else {
            this.out = new FSDataOutputStream(checksumOut,null);
          }
          
          this.priorityClass = priorityClass;
          this.keyClass = keyClass;
          this.valueClass = valueClass;
          SerializationFactory serializationFactory = new SerializationFactory(conf);
          this.prioritySerializer = serializationFactory.getSerializer(priorityClass);
          this.prioritySerializer.open(buffer);
          this.keySerializer = serializationFactory.getSerializer(keyClass);
          this.keySerializer.open(buffer);
          this.valueSerializer = serializationFactory.getSerializer(valueClass);
          this.valueSerializer.open(buffer);
        }
    
    public void close() throws IOException {
	      // Close the serializers
      prioritySerializer.close();
      keySerializer.close();
      valueSerializer.close();

      WritableUtils.writeVInt(out, EOF_MARKER);
      WritableUtils.writeVInt(out, EOF_MARKER);
      WritableUtils.writeVInt(out, EOF_MARKER);
      decompressedBytesWritten += 3 * WritableUtils.getVIntSize(EOF_MARKER);
      
      //Flush the stream
      out.flush();
  
      if (compressOutput) {
        // Flush
        compressedOut.finish();
        compressedOut.resetState();
      }
      
      // Close the underlying stream iff we own it...
      if (ownOutputStream) {
        out.close();
      }
      else {
        // Write the checksum
        checksumOut.finish();
      }

      compressedBytesWritten = rawOut.getPos() - start;

      if (compressOutput) {
        // Return back the compressor
        CodecPool.returnCompressor(compressor);
        compressor = null;
      }

      out = null;
      if(writtenRecordsCounter != null) {
        writtenRecordsCounter.increment(numRecordsWritten);
      }
    }

    //add for supporting priority
    public void append(P p, K key, V value) throws IOException {
        if (p.getClass() != priorityClass)
            throw new IOException("wrong priority class: "+ p.getClass()
                                  +" is not "+ priorityClass);
        if (key.getClass() != keyClass)
          throw new IOException("wrong key class: "+ key.getClass()
                                +" is not "+ keyClass);
        if (value.getClass() != valueClass)
          throw new IOException("wrong value class: "+ value.getClass()
                                +" is not "+ valueClass);

        //Append the 'priority'
        prioritySerializer.serialize(p);
        int priorityLength = buffer.getLength();
        if (priorityLength < 0) {
          throw new IOException("Negative priority-length not allowed: " + priorityLength + 
                                " for " + p);
        }
        
        // Append the 'key'
        keySerializer.serialize(key);
        int keyLength = buffer.getLength() - priorityLength;
        if (keyLength < 0) {
          throw new IOException("Negative key-length not allowed: " + keyLength + 
                                " for " + key);
        }

        // Append the 'value'
        valueSerializer.serialize(value);
        int valueLength = buffer.getLength() - keyLength - priorityLength;
        if (valueLength < 0) {
          throw new IOException("Negative value-length not allowed: " + 
                                valueLength + " for " + value);
        }
        
        // Write the record out
       
        WritableUtils.writeVInt(out, priorityLength);						  // priority
        WritableUtils.writeVInt(out, keyLength);                  // key length
        WritableUtils.writeVInt(out, valueLength);                // value length
        out.write(buffer.getData(), 0, buffer.getLength());       // data

        out.flush();
        
        // Reset
        buffer.reset();
        
        // Update bytes written
        decompressedBytesWritten += priorityLength + keyLength + valueLength + 
        							WritableUtils.getVIntSize(priorityLength) + 
                                    WritableUtils.getVIntSize(keyLength) + 
                                    WritableUtils.getVIntSize(valueLength);
        ++numRecordsWritten;
      }
    
    public long getRawLength() {
      return decompressedBytesWritten;
    }
    
    public long getCompressedLength() {
      return compressedBytesWritten;
    }
  }

  public static class StateDataWriter<K extends Object, V extends Object> {
	    FSDataOutputStream out;
	    boolean ownOutputStream = false;
	    long start = 0;
	    FSDataOutputStream rawOut;
	    
	    CompressionOutputStream compressedOut;
	    Compressor compressor;
	    boolean compressOutput = false;
	    
	    long decompressedBytesWritten = 0;
	    long compressedBytesWritten = 0;

	    // Count records written to disk
	    private long numRecordsWritten = 0;
	    private final Counters.Counter writtenRecordsCounter;

	    IFileOutputStream checksumOut;

	    Class<K> keyClass;  
	    Class<V> valueClass;
	    Serializer<K> keySerializer;
	    Serializer<V> iStateSerializer;
	    Serializer<V> cStateSerializer;
	    
	    DataOutputBuffer buffer = new DataOutputBuffer();
	    
	    public StateDataWriter(Configuration conf, FileSystem fs, Path file, 
	            Class<K> keyClass, Class<V> valueClass,
	            CompressionCodec codec,
	            Counters.Counter writesCounter) throws IOException {
			this(conf, fs.create(file), keyClass, valueClass, codec,
			     writesCounter);
			ownOutputStream = true;
		}
	    
	    public StateDataWriter(Configuration conf, FSDataOutputStream out, 
	    		Class<K> keyClass,
	            Class<V> valueClass,
	            CompressionCodec codec, Counters.Counter writesCounter)
	            throws IOException {
	          this.writtenRecordsCounter = writesCounter;
	          this.checksumOut = new IFileOutputStream(out);
	          this.rawOut = out;
	          this.start = this.rawOut.getPos();
	          
	          if (codec != null) {
	            this.compressor = CodecPool.getCompressor(codec);
	            this.compressor.reset();
	            this.compressedOut = codec.createOutputStream(checksumOut, compressor);
	            this.out = new FSDataOutputStream(this.compressedOut,  null);
	            this.compressOutput = true;
	          } else {
	            this.out = new FSDataOutputStream(checksumOut,null);
	          }
	          
	          this.keyClass = keyClass;
	          this.valueClass = valueClass;
	          SerializationFactory serializationFactory = new SerializationFactory(conf);
	          this.keySerializer = serializationFactory.getSerializer(keyClass);
	          this.keySerializer.open(buffer);
	          this.iStateSerializer = serializationFactory.getSerializer(valueClass);
	          this.iStateSerializer.open(buffer);
	          this.cStateSerializer = serializationFactory.getSerializer(valueClass);
	          this.cStateSerializer.open(buffer);
	        }
	    
	    public void close() throws IOException {
		      // Close the serializers
		      keySerializer.close();
		      iStateSerializer.close();
		      cStateSerializer.close();

		      WritableUtils.writeVInt(out, EOF_MARKER);
		      WritableUtils.writeVInt(out, EOF_MARKER);
		      WritableUtils.writeVInt(out, EOF_MARKER);
		      decompressedBytesWritten += 3 * WritableUtils.getVIntSize(EOF_MARKER);
	      
	      //Flush the stream
	      out.flush();
	  
	      if (compressOutput) {
	        // Flush
	        compressedOut.finish();
	        compressedOut.resetState();
	      }
	      
	      // Close the underlying stream iff we own it...
	      if (ownOutputStream) {
	        out.close();
	      }
	      else {
	        // Write the checksum
	        checksumOut.finish();
	      }

	      compressedBytesWritten = rawOut.getPos() - start;

	      if (compressOutput) {
	        // Return back the compressor
	        CodecPool.returnCompressor(compressor);
	        compressor = null;
	      }

	      out = null;
	      if(writtenRecordsCounter != null) {
	        writtenRecordsCounter.increment(numRecordsWritten);
	      }
	    }

	    //add for supporting priority
	    public void append(K key, V iState, V cState) throws IOException {
	        if (key.getClass() != keyClass)
	          throw new IOException("wrong key class: "+ key.getClass()
	                                +" is not "+ keyClass);
	        if (iState.getClass() != valueClass)
		          throw new IOException("wrong istate class: "+ iState.getClass()
		                                +" is not "+ valueClass);
	        if (cState.getClass() != valueClass)
	          throw new IOException("wrong cstate class: "+ cState.getClass()
	                                +" is not "+ valueClass);

	        // Append the 'key'
	        keySerializer.serialize(key);
	        int keyLength = buffer.getLength();
	        if (keyLength < 0) {
	          throw new IOException("Negative key-length not allowed: " + keyLength + 
	                                " for " + key);
	        }

	        int exelen = keyLength;
	        
	        // Append the 'iState'
	        iStateSerializer.serialize(iState);
	        int iStateLength = buffer.getLength() - exelen;
	        if (iStateLength < 0) {
	          throw new IOException("Negative value-length not allowed: " + 
	        		  iStateLength + " for " + iState);
	        }
	        exelen = buffer.getLength();
	        
	        // Append the 'cState'
	        cStateSerializer.serialize(cState);
	        int cStateLength = buffer.getLength() - exelen;
	        if (cStateLength < 0) {
	          throw new IOException("Negative value-length not allowed: " + 
	        		  cStateLength + " for " + cState);
	        }
	        exelen = buffer.getLength();
	        
	        // Write the record out
	        WritableUtils.writeVInt(out, keyLength);                  // key length
	        WritableUtils.writeVInt(out, iStateLength);                // cState length
	        WritableUtils.writeVInt(out, cStateLength);                // cState length
	        out.write(buffer.getData(), 0, buffer.getLength());       

	        out.flush();
	        
	        // Reset
	        buffer.reset();
	        
	        // Update bytes written
	        decompressedBytesWritten += exelen +
	                                    WritableUtils.getVIntSize(keyLength) + 
	                                    WritableUtils.getVIntSize(iStateLength) +
	        							WritableUtils.getVIntSize(cStateLength); 
	        ++numRecordsWritten;
	      }
	    
	    public long getRawLength() {
	      return decompressedBytesWritten;
	    }
	    
	    public long getCompressedLength() {
	      return compressedBytesWritten;
	    }
	  }
  
  public static class PriorityQueueWriter<K extends Object, V extends Object, D extends Object> {
	    FSDataOutputStream out;
	    boolean ownOutputStream = false;
	    long start = 0;
	    FSDataOutputStream rawOut;
	    
	    CompressionOutputStream compressedOut;
	    Compressor compressor;
	    boolean compressOutput = false;
	    
	    long decompressedBytesWritten = 0;
	    long compressedBytesWritten = 0;

	    // Count records written to disk
	    private long numRecordsWritten = 0;
	    private final Counters.Counter writtenRecordsCounter;

	    IFileOutputStream checksumOut;

	    Class<K> keyClass;  
	    Class<V> valueClass;
	    Class<D> dataClass;
	    Serializer<K> keySerializer;
	    Serializer<V> iStateSerializer;
	    Serializer<D> dataSerializer;
	    
	    DataOutputBuffer buffer = new DataOutputBuffer();
	    
	    public PriorityQueueWriter(Configuration conf, FileSystem fs, Path file, 
                Class<K> keyClass, Class<V> valueClass, Class<D> dataClass,
                CompressionCodec codec,
                Counters.Counter writesCounter) throws IOException {
		    this(conf, fs.create(file), keyClass, valueClass, dataClass, codec,
		         writesCounter);
		    ownOutputStream = true;
		  }
	    
	    public PriorityQueueWriter(Configuration conf, FSDataOutputStream out, 
	    		Class<K> keyClass,
	            Class<V> valueClass,
	            Class<D> dataClass,
	            CompressionCodec codec, Counters.Counter writesCounter)
	            throws IOException {
	          this.writtenRecordsCounter = writesCounter;
	          this.checksumOut = new IFileOutputStream(out);
	          this.rawOut = out;
	          this.start = this.rawOut.getPos();
	          
	          if (codec != null) {
	            this.compressor = CodecPool.getCompressor(codec);
	            this.compressor.reset();
	            this.compressedOut = codec.createOutputStream(checksumOut, compressor);
	            this.out = new FSDataOutputStream(this.compressedOut,  null);
	            this.compressOutput = true;
	          } else {
	            this.out = new FSDataOutputStream(checksumOut,null);
	          }
	          
	          this.keyClass = keyClass;
	          this.valueClass = valueClass;
	          this.dataClass = dataClass;
	          SerializationFactory serializationFactory = new SerializationFactory(conf);
	          this.keySerializer = serializationFactory.getSerializer(keyClass);
	          this.keySerializer.open(buffer);
	          this.iStateSerializer = serializationFactory.getSerializer(valueClass);
	          this.iStateSerializer.open(buffer);
	          this.dataSerializer = serializationFactory.getSerializer(dataClass);
	          this.dataSerializer.open(buffer);
	        }
	    
	    public void close() throws IOException {
		      // Close the serializers
		      keySerializer.close();
		      iStateSerializer.close();
		      dataSerializer.close();

		      WritableUtils.writeVInt(out, EOF_MARKER);
		      WritableUtils.writeVInt(out, EOF_MARKER);
		      WritableUtils.writeVInt(out, EOF_MARKER);
		      decompressedBytesWritten += 3 * WritableUtils.getVIntSize(EOF_MARKER);
	      
	      //Flush the stream
	      out.flush();
	  
	      if (compressOutput) {
	        // Flush
	        compressedOut.finish();
	        compressedOut.resetState();
	      }
	      
	      // Close the underlying stream iff we own it...
	      if (ownOutputStream) {
	        out.close();
	      }
	      else {
	        // Write the checksum
	        checksumOut.finish();
	      }

	      compressedBytesWritten = rawOut.getPos() - start;

	      if (compressOutput) {
	        // Return back the compressor
	        CodecPool.returnCompressor(compressor);
	        compressor = null;
	      }

	      out = null;
	      if(writtenRecordsCounter != null) {
	        writtenRecordsCounter.increment(numRecordsWritten);
	      }
	    }

	    //add for supporting priority
	    public void append(K key, V iState, D data) throws IOException {
	        if (key.getClass() != keyClass)
	          throw new IOException("wrong key class: "+ key.getClass()
	                                +" is not "+ keyClass);
	        if (iState.getClass() != valueClass)
		          throw new IOException("wrong istate class: "+ iState.getClass()
		                                +" is not "+ valueClass);
	        if (data.getClass() != dataClass)
	          throw new IOException("wrong data class: "+ data.getClass()
	                                +" is not "+ dataClass);

	        // Append the 'key'
	        keySerializer.serialize(key);
	        int keyLength = buffer.getLength();
	        if (keyLength < 0) {
	          throw new IOException("Negative key-length not allowed: " + keyLength + 
	                                " for " + key);
	        }

	        int exelen = keyLength;
	        
	        // Append the 'iState'
	        iStateSerializer.serialize(iState);
	        int iStateLength = buffer.getLength() - exelen;
	        if (iStateLength < 0) {
	          throw new IOException("Negative value-length not allowed: " + 
	        		  iStateLength + " for " + iState);
	        }
	        exelen = buffer.getLength();
	        
	        // Append the 'cState'
	        dataSerializer.serialize(data);
	        int dataLength = buffer.getLength() - exelen;
	        if (dataLength < 0) {
	          throw new IOException("Negative value-length not allowed: " + 
	        		  dataLength + " for " + data);
	        }
	        exelen = buffer.getLength();
	        
	        // Write the record out
	        WritableUtils.writeVInt(out, keyLength);                  // key length
	        WritableUtils.writeVInt(out, iStateLength);                // cState length
	        WritableUtils.writeVInt(out, dataLength);                // cState length
	        out.write(buffer.getData(), 0, buffer.getLength());       

	        out.flush();
	        
	        // Reset
	        buffer.reset();
	        
	        // Update bytes written
	        decompressedBytesWritten += exelen +
	                                    WritableUtils.getVIntSize(keyLength) + 
	                                    WritableUtils.getVIntSize(iStateLength) +
	        							WritableUtils.getVIntSize(dataLength); 
	        ++numRecordsWritten;
	      }
	    
	    public long getRawLength() {
	      return decompressedBytesWritten;
	    }
	    
	    public long getCompressedLength() {
	      return compressedBytesWritten;
	    }
	  }
  
  public static class StaticDataWriter<K extends Object, D extends Object> {
	    FSDataOutputStream out;
	    boolean ownOutputStream = false;
	    long start = 0;
	    FSDataOutputStream rawOut;
	    
	    CompressionOutputStream compressedOut;
	    Compressor compressor;
	    boolean compressOutput = false;
	    
	    long decompressedBytesWritten = 0;
	    long compressedBytesWritten = 0;

	    // Count records written to disk
	    private long numRecordsWritten = 0;
	    private final Counters.Counter writtenRecordsCounter;

	    IFileOutputStream checksumOut;

	    Class<K> keyClass;  
	    Class<D> dataClass;
	    Serializer<K> keySerializer;
	    Serializer<D> dataSerializer;
	    
	    DataOutputBuffer buffer = new DataOutputBuffer();
	    
	    public StaticDataWriter(Configuration conf, FSDataOutputStream out, 
	    		Class<K> keyClass,
	            Class<D> dataClass,
	            CompressionCodec codec, Counters.Counter writesCounter)
	            throws IOException {
	          this.writtenRecordsCounter = writesCounter;
	          this.checksumOut = new IFileOutputStream(out);
	          this.rawOut = out;
	          this.start = this.rawOut.getPos();
	          
	          if (codec != null) {
	            this.compressor = CodecPool.getCompressor(codec);
	            this.compressor.reset();
	            this.compressedOut = codec.createOutputStream(checksumOut, compressor);
	            this.out = new FSDataOutputStream(this.compressedOut,  null);
	            this.compressOutput = true;
	          } else {
	            this.out = new FSDataOutputStream(checksumOut,null);
	          }
	          
	          this.keyClass = keyClass;
	          this.dataClass = dataClass;
	          SerializationFactory serializationFactory = new SerializationFactory(conf);
	          this.keySerializer = serializationFactory.getSerializer(keyClass);
	          this.keySerializer.open(buffer);
	          this.dataSerializer = serializationFactory.getSerializer(dataClass);
	          this.dataSerializer.open(buffer);
	        }
	    
	    public void close() throws IOException {
		      // Close the serializers
		      keySerializer.close();
		      dataSerializer.close();

		      WritableUtils.writeVInt(out, EOF_MARKER);
		      WritableUtils.writeVInt(out, EOF_MARKER);
		      decompressedBytesWritten += 2 * WritableUtils.getVIntSize(EOF_MARKER);
	      
	      //Flush the stream
	      out.flush();
	  
	      if (compressOutput) {
	        // Flush
	        compressedOut.finish();
	        compressedOut.resetState();
	      }
	      
	      // Close the underlying stream iff we own it...
	      if (ownOutputStream) {
	        out.close();
	      }
	      else {
	        // Write the checksum
	        checksumOut.finish();
	      }

	      compressedBytesWritten = rawOut.getPos() - start;

	      if (compressOutput) {
	        // Return back the compressor
	        CodecPool.returnCompressor(compressor);
	        compressor = null;
	      }

	      out = null;
	      if(writtenRecordsCounter != null) {
	        writtenRecordsCounter.increment(numRecordsWritten);
	      }
	    }

	    //add for supporting priority
	    public void append(K key, D data) throws IOException {
	        if (key.getClass() != keyClass)
	          throw new IOException("wrong key class: "+ key.getClass()
	                                +" is not "+ keyClass);
	        if (data.getClass() != dataClass)
		          throw new IOException("wrong istate class: "+ data.getClass()
		                                +" is not "+ dataClass);

	        // Append the 'key'
	        keySerializer.serialize(key);
	        int keyLength = buffer.getLength();
	        if (keyLength < 0) {
	          throw new IOException("Negative key-length not allowed: " + keyLength + 
	                                " for " + key);
	        }

	        int exelen = keyLength;
	        
	        // Append the 'data'
	        dataSerializer.serialize(data);
	        int dataLength = buffer.getLength() - exelen;
	        if (dataLength < 0) {
	          throw new IOException("Negative value-length not allowed: " + 
	        		  dataLength + " for " + data);
	        }
	        exelen = buffer.getLength();
	        
	        // Write the record out
	        WritableUtils.writeVInt(out, keyLength);                  // key length
	        WritableUtils.writeVInt(out, dataLength);                // data length
	        out.write(buffer.getData(), 0, buffer.getLength());       

	        out.flush();
	        
	        // Reset
	        buffer.reset();
	        
	        // Update bytes written
	        decompressedBytesWritten += exelen +
	                                    WritableUtils.getVIntSize(keyLength) + 
	                                    WritableUtils.getVIntSize(dataLength); 
	        ++numRecordsWritten;
	      }
	    
	    public long getRawLength() {
	      return decompressedBytesWritten;
	    }
	    
	    public long getCompressedLength() {
	      return compressedBytesWritten;
	    }
	  }
  
  public static class Reader<K extends Object, V extends Object> {
	    private static final int DEFAULT_BUFFER_SIZE = 128*1024;
	    private static final int MAX_VINT_SIZE = 9;

	    // Count records read from disk
	    private long numRecordsRead = 0;
	    private final Counters.Counter readRecordsCounter;

	    final InputStream in;        // Possibly decompressed stream that we read
	    Decompressor decompressor;
	    protected long bytesRead = 0;
	    final long fileLength;
	    boolean eof = false;
	    final IFileInputStream checksumIn;
	    
	    byte[] buffer = null;
	    int bufferSize = DEFAULT_BUFFER_SIZE;
	    DataInputBuffer dataIn = new DataInputBuffer();

	    int recNo = 1;
	    
	    /**
	     * Construct an IFile Reader.
	     * 
	     * @param conf Configuration File 
	     * @param fs  FileSystem
	     * @param file Path of the file to be opened. This file should have
	     *             checksum bytes for the data at the end of the file.
	     * @param codec codec
	     * @param readsCounter Counter for records read from disk
	     * @throws IOException
	     */
	    public Reader(Configuration conf, FileSystem fs, Path file,
	                  CompressionCodec codec,
	                  Counters.Counter readsCounter) throws IOException {
	      this(conf, fs.open(file), 
	           fs.getFileStatus(file).getLen(),
	           codec, readsCounter);
	    }

	    /**
	     * Construct an IFile Reader.
	     * 
	     * @param conf Configuration File 
	     * @param in   The input stream
	     * @param length Length of the data in the stream, including the checksum
	     *               bytes.
	     * @param codec codec
	     * @param readsCounter Counter for records read from disk
	     * @throws IOException
	     */
	    public Reader(Configuration conf, DataInputStream in, long length, 
	                  CompressionCodec codec,
	                  Counters.Counter readsCounter) throws IOException {
	      readRecordsCounter = readsCounter;
	      checksumIn = new IFileInputStream(in,length);
	      if (codec != null) {
	        decompressor = CodecPool.getDecompressor(codec);
	        this.in = codec.createInputStream(checksumIn, decompressor);
	      } else {
	        this.in = checksumIn;
	      }
	      this.fileLength = length;
	      
	      if (conf != null) {
	        bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
	      }
	    }
	    
	    public long getLength() { 
	      return fileLength - checksumIn.getSize();
	    }
	    
	    public long getPosition() throws IOException {    
	      return checksumIn.getPosition(); 
	    }
	    
	    /**
	     * Read upto len bytes into buf starting at offset off.
	     * 
	     * @param buf buffer 
	     * @param off offset
	     * @param len length of buffer
	     * @return the no. of bytes read
	     * @throws IOException
	     */
	    private int readData(byte[] buf, int off, int len) throws IOException {
	      int bytesRead = 0;
	      while (bytesRead < len) {
	        int n = in.read(buf, off+bytesRead, len-bytesRead);
	        if (n < 0) {
	          return bytesRead;
	        }
	        bytesRead += n;
	      }
	      return len;
	    }
	    
	    void readNextBlock(int minSize) throws IOException {
	      if (buffer == null) {
	        buffer = new byte[bufferSize];
	        dataIn.reset(buffer, 0, 0);
	      }
	      buffer = 
	        rejigData(buffer, 
	                  (bufferSize < minSize) ? new byte[minSize << 1] : buffer);
	      bufferSize = buffer.length;
	    }
	    
	    private byte[] rejigData(byte[] source, byte[] destination) 
	    throws IOException{
	      // Copy remaining data into the destination array
	      int bytesRemaining = dataIn.getLength()-dataIn.getPosition();
	      if (bytesRemaining > 0) {
	        System.arraycopy(source, dataIn.getPosition(), 
	            destination, 0, bytesRemaining);
	      }
	      
	      // Read as much data as will fit from the underlying stream 
	      int n = readData(destination, bytesRemaining, 
	                       (destination.length - bytesRemaining));
	      dataIn.reset(destination, 0, (bytesRemaining + n));
	      
	      return destination;
	    }
	    
	    public boolean next(DataInputBuffer key, DataInputBuffer value) 
	    throws IOException {
	      // Sanity check
	      if (eof) {
	        throw new EOFException("Completed reading " + bytesRead);
	      }
	      
	      // Check if we have enough data to read lengths
	      if ((dataIn.getLength() - dataIn.getPosition()) < 2*MAX_VINT_SIZE) {
	        readNextBlock(2*MAX_VINT_SIZE);
	      }
	      
	      // Read key and value lengths
	      int oldPos = dataIn.getPosition();
	      int keyLength = WritableUtils.readVInt(dataIn);
	      int valueLength = WritableUtils.readVInt(dataIn);
	      int pos = dataIn.getPosition();
	      bytesRead += pos - oldPos;
	      
	      // Check for EOF
	      if (keyLength == EOF_MARKER && valueLength == EOF_MARKER) {
	        eof = true;
	        return false;
	      }
	      
	      // Sanity check
	      if (keyLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
	                              keyLength);
	      }
	      if (valueLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative value-length: " + 
	                              valueLength);
	      }
	      
	      final int recordLength = keyLength + valueLength;
	      
	      // Check if we have the raw key/value in the buffer
	      if ((dataIn.getLength()-pos) < recordLength) {
	        readNextBlock(recordLength);
	        
	        // Sanity check
	        if ((dataIn.getLength() - dataIn.getPosition()) < recordLength) {
	          throw new EOFException("Rec# " + recNo + ": Could read the next " +
	          		                   " record");
	        }
	      }

	      // Setup the key and value
	      pos = dataIn.getPosition();
	      byte[] data = dataIn.getData();
	      key.reset(data, pos, keyLength);
	      value.reset(data, (pos + keyLength), valueLength);
	      
	      // Position for the next record
	      long skipped = dataIn.skip(recordLength);
	      if (skipped != recordLength) {
	        throw new IOException("Rec# " + recNo + ": Failed to skip past record " +
	        		                  "of length: " + recordLength);
	      }
	      
	      // Record the bytes read
	      bytesRead += recordLength;

	      ++recNo;
	      ++numRecordsRead;

	      return true;
	    }
	    
	    public void close() throws IOException {
	      // Return the decompressor
	      if (decompressor != null) {
	        decompressor.reset();
	        CodecPool.returnDecompressor(decompressor);
	        decompressor = null;
	      }
	      
	      // Close the underlying stream
	      in.close();
	      
	      // Release the buffer
	      dataIn = null;
	      buffer = null;
	      if(readRecordsCounter != null) {
	        readRecordsCounter.increment(numRecordsRead);
	      }
	    }
	  } 
  
  public static class SocketReader<K extends Object, V extends Object> {
	    private static final int DEFAULT_BUFFER_SIZE = 128*1024;
	    private static final int MAX_VINT_SIZE = 9;

	    // Count records read from disk
	    private long numRecordsRead = 0;
	    private final Counters.Counter readRecordsCounter;

	    final InputStream in;        // Possibly decompressed stream that we read
	    Decompressor decompressor;
	    protected long bytesRead = 0;
	    final long fileLength;
	    boolean eof = false;
	    final IFileInputStream checksumIn;
	    
	    byte[] buffer = null;
	    int bufferSize = DEFAULT_BUFFER_SIZE;
	    DataInputBuffer dataIn = new DataInputBuffer();

	    int recNo = 1;

	    /**
	     * Construct an IFile Reader.
	     * 
	     * @param conf Configuration File 
	     * @param in   The input stream
	     * @param length Length of the data in the stream, including the checksum
	     *               bytes.
	     * @param codec codec
	     * @param readsCounter Counter for records read from disk
	     * @throws IOException
	     */
	    public SocketReader(Configuration conf, DataInputStream in, long length, 
	                  CompressionCodec codec,
	                  Counters.Counter readsCounter) throws IOException {
	      readRecordsCounter = readsCounter;
	      checksumIn = new IFileInputStream(in,length);
	      if (codec != null) {
	        decompressor = CodecPool.getDecompressor(codec);
	        this.in = codec.createInputStream(checksumIn, decompressor);
	      } else {
	        this.in = checksumIn;
	      }
	      this.fileLength = length;
	      
	      if (conf != null) {
	        bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
	      }
	    }
	    
	    public long getLength() { 
	      return fileLength - checksumIn.getSize();
	    }
	    
	    public long getPosition() throws IOException {    
	      return checksumIn.getPosition(); 
	    }
	    
	    /**
	     * Read upto len bytes into buf starting at offset off.
	     * 
	     * @param buf buffer 
	     * @param off offset
	     * @param len length of buffer
	     * @return the no. of bytes read
	     * @throws IOException
	     */
	    private int readData(byte[] buf, int off, int len) throws IOException {
	      int bytesRead = 0;
	      while (bytesRead < len) {
	        int n = in.read(buf, off+bytesRead, len-bytesRead);
	        if (n < 0) {
	          return bytesRead;
	        }
	        bytesRead += n;
	      }
	      return len;
	    }
	    
	    void readNextBlock(int minSize) throws IOException {
	      if (buffer == null) {
	        buffer = new byte[bufferSize];
	        dataIn.reset(buffer, 0, 0);
	      }
	      buffer = 
	        rejigData(buffer, 
	                  (bufferSize < minSize) ? new byte[minSize << 1] : buffer);
	      bufferSize = buffer.length;
	    }
	    
	    private byte[] rejigData(byte[] source, byte[] destination) 
	    throws IOException{
	      // Copy remaining data into the destination array
	      int bytesRemaining = dataIn.getLength()-dataIn.getPosition();
	      if (bytesRemaining > 0) {
	        System.arraycopy(source, dataIn.getPosition(), 
	            destination, 0, bytesRemaining);
	      }
	      
	      // Read as much data as will fit from the underlying stream 
	      int n = readData(destination, bytesRemaining, 
	                       (destination.length - bytesRemaining));
	      dataIn.reset(destination, 0, (bytesRemaining + n));
	      
	      return destination;
	    }
	    
	    public boolean next(DataInputBuffer key, DataInputBuffer value) 
	    throws IOException {
	      // Sanity check
	      if (eof) {
	        throw new EOFException("Completed reading " + bytesRead);
	      }
	      
	      // Check if we have enough data to read lengths
	      if ((dataIn.getLength() - dataIn.getPosition()) < 2*MAX_VINT_SIZE) {
	        readNextBlock(2*MAX_VINT_SIZE);
	      }
	      
	      // Read key and value lengths
	      int oldPos = dataIn.getPosition();
	      int keyLength = WritableUtils.readVInt(dataIn);
	      int valueLength = WritableUtils.readVInt(dataIn);
	      int pos = dataIn.getPosition();
	      bytesRead += pos - oldPos;
	      
	      // Check for EOF
	      if (keyLength == EOF_MARKER && valueLength == EOF_MARKER) {
	        eof = true;
	        return false;
	      }
	      
	      // Sanity check
	      if (keyLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
	                              keyLength);
	      }
	      if (valueLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative value-length: " + 
	                              valueLength);
	      }
	      
	      final int recordLength = keyLength + valueLength;
	      
	      // Check if we have the raw key/value in the buffer
	      if ((dataIn.getLength()-pos) < recordLength) {
	        readNextBlock(recordLength);
	        
	        // Sanity check
	        if ((dataIn.getLength() - dataIn.getPosition()) < recordLength) {
	          throw new EOFException("Rec# " + recNo + ": Could read the next " +
	          		                   " record");
	        }
	      }

	      // Setup the key and value
	      pos = dataIn.getPosition();
	      byte[] data = dataIn.getData();
	      key.reset(data, pos, keyLength);
	      value.reset(data, (pos + keyLength), valueLength);
	      
	      // Position for the next record
	      long skipped = dataIn.skip(recordLength);
	      if (skipped != recordLength) {
	        throw new IOException("Rec# " + recNo + ": Failed to skip past record " +
	        		                  "of length: " + recordLength);
	      }
	      
	      // Record the bytes read
	      bytesRead += recordLength;

	      ++recNo;
	      ++numRecordsRead;

	      return true;
	    }
	    
	    public void close() throws IOException {
	      // Return the decompressor
	      if (decompressor != null) {
	        decompressor.reset();
	        CodecPool.returnDecompressor(decompressor);
	        decompressor = null;
	      }
	      
	      // Close the underlying stream
	      in.close();
	      
	      // Release the buffer
	      dataIn = null;
	      buffer = null;
	      if(readRecordsCounter != null) {
	        readRecordsCounter.increment(numRecordsRead);
	      }
	    }
	  } 
  
  /**
   * <code>IFile.Reader</code> to read intermediate map-outputs. 
   */
  public static class PriorityReader<P extends Object, K extends Object, V extends Object> {
    private static final int DEFAULT_BUFFER_SIZE = 128*1024;
    private static final int MAX_VINT_SIZE = 9;

    // Count records read from disk
    private long numRecordsRead = 0;
    private final Counters.Counter readRecordsCounter;

    final InputStream in;        // Possibly decompressed stream that we read
    Decompressor decompressor;
    protected long bytesRead = 0;
    final long fileLength;
    boolean eof = false;
    final IFileInputStream checksumIn;
    
    byte[] buffer = null;
    int bufferSize = DEFAULT_BUFFER_SIZE;
    DataInputBuffer dataIn = new DataInputBuffer();

    int recNo = 1;
    
    /**
     * Construct an IFile Reader.
     * 
     * @param conf Configuration File 
     * @param fs  FileSystem
     * @param file Path of the file to be opened. This file should have
     *             checksum bytes for the data at the end of the file.
     * @param codec codec
     * @param readsCounter Counter for records read from disk
     * @throws IOException
     */
    public PriorityReader(Configuration conf, FileSystem fs, Path file,
                  CompressionCodec codec,
                  Counters.Counter readsCounter) throws IOException {
      this(conf, fs.open(file), 
           fs.getFileStatus(file).getLen(),
           codec, readsCounter);
    }

    /**
     * Construct an IFile Reader.
     * 
     * @param conf Configuration File 
     * @param in   The input stream
     * @param length Length of the data in the stream, including the checksum
     *               bytes.
     * @param codec codec
     * @param readsCounter Counter for records read from disk
     * @throws IOException
     */
    public PriorityReader(Configuration conf, DataInputStream in, long length, 
                  CompressionCodec codec,
                  Counters.Counter readsCounter) throws IOException {
      readRecordsCounter = readsCounter;
      checksumIn = new IFileInputStream(in,length);
      if (codec != null) {
        decompressor = CodecPool.getDecompressor(codec);
        this.in = codec.createInputStream(checksumIn, decompressor);
      } else {
        this.in = checksumIn;
      }
      this.fileLength = length;
      
      if (conf != null) {
        bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
      }
    }
    
    public long getLength() { 
      return fileLength - checksumIn.getSize();
    }
    
    public long getPosition() throws IOException {    
      return checksumIn.getPosition(); 
    }
    
    /**
     * Read upto len bytes into buf starting at offset off.
     * 
     * @param buf buffer 
     * @param off offset
     * @param len length of buffer
     * @return the no. of bytes read
     * @throws IOException
     */
    private int readData(byte[] buf, int off, int len) throws IOException {
      int bytesRead = 0;
      while (bytesRead < len) {
        int n = in.read(buf, off+bytesRead, len-bytesRead);
        if (n < 0) {
          return bytesRead;
        }
        bytesRead += n;
      }
      return len;
    }
    
    void readNextBlock(int minSize) throws IOException {
      if (buffer == null) {
        buffer = new byte[bufferSize];
        dataIn.reset(buffer, 0, 0);
      }
      buffer = 
        rejigData(buffer, 
                  (bufferSize < minSize) ? new byte[minSize << 1] : buffer);
      bufferSize = buffer.length;
    }
    
    private byte[] rejigData(byte[] source, byte[] destination) 
    throws IOException{
      // Copy remaining data into the destination array
      int bytesRemaining = dataIn.getLength()-dataIn.getPosition();
      if (bytesRemaining > 0) {
        System.arraycopy(source, dataIn.getPosition(), 
            destination, 0, bytesRemaining);
      }
      
      // Read as much data as will fit from the underlying stream 
      int n = readData(destination, bytesRemaining, 
                       (destination.length - bytesRemaining));
      dataIn.reset(destination, 0, (bytesRemaining + n));
      
      return destination;
    }

    public boolean next(DataInputBuffer priority, DataInputBuffer key, DataInputBuffer value) 
    throws IOException {
      // Sanity check
      if (eof) {
        throw new EOFException("Completed reading " + bytesRead);
      }
      
      // Check if we have enough data to read lengths
      if ((dataIn.getLength() - dataIn.getPosition()) < 3*MAX_VINT_SIZE) {
        readNextBlock(3*MAX_VINT_SIZE);
      }
      
      // Read key and value lengths
      int oldPos = dataIn.getPosition();
      
      int priorityLength = WritableUtils.readVInt(dataIn);
      int keyLength = WritableUtils.readVInt(dataIn);
      int valueLength = WritableUtils.readVInt(dataIn);
      int pos = dataIn.getPosition();
      bytesRead += pos - oldPos;
      
      // Check for EOF
      if (priorityLength == EOF_MARKER && keyLength == EOF_MARKER && valueLength == EOF_MARKER) {
        eof = true;
        return false;
      }
      
      // Sanity check
      if (priorityLength < 0) {
          throw new IOException("Rec# " + recNo + ": Negative priority-length: " + 
                                priorityLength);
      }
      if (keyLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
                              keyLength);
      }
      if (valueLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative value-length: " + 
                              valueLength);
      }
      
      final int recordLength = priorityLength + keyLength + valueLength;
      
      // Check if we have the raw key/value in the buffer
      if ((dataIn.getLength()-pos) < recordLength) {
        readNextBlock(recordLength);
        
        // Sanity check
        if ((dataIn.getLength() - dataIn.getPosition()) < recordLength) {
          throw new EOFException("Rec# " + recNo + ": Could read the next " +
          		                   " record");
        }
      }

      // Setup the key and value
      pos = dataIn.getPosition();
      byte[] data = dataIn.getData();
      priority.reset(data, pos, priorityLength);
      key.reset(data, (pos + priorityLength), keyLength);
      value.reset(data, (pos + priorityLength + keyLength), valueLength);
      
      // Position for the next record
      long skipped = dataIn.skip(recordLength);
      if (skipped != recordLength) {
        throw new IOException("Rec# " + recNo + ": Failed to skip past record " +
        		                  "of length: " + recordLength);
      }
      
      // Record the bytes read
      bytesRead += recordLength;			//priority excluded

      ++recNo;
      ++numRecordsRead;

      return true;
    }
    
    public void close() throws IOException {
      // Return the decompressor
      if (decompressor != null) {
        decompressor.reset();
        CodecPool.returnDecompressor(decompressor);
        decompressor = null;
      }
      
      // Close the underlying stream
      in.close();
      
      // Release the buffer
      dataIn = null;
      buffer = null;
      if(readRecordsCounter != null) {
        readRecordsCounter.increment(numRecordsRead);
      }
    }
  }    
  
  /**
   * <code>IFile.InMemoryReader</code> to read map-outputs present in-memory.
   */
  public static class InMemoryReader<K, V> extends Reader<K, V> {
    RamManager ramManager;
    TaskID taskid;
    
    public InMemoryReader(RamManager ramManager, TaskID taskid,
                          byte[] data, int start, int length)
                          throws IOException {
      super(null, null, length - start, null, null);
      this.ramManager = ramManager;
      this.taskid = taskid;
      
      buffer = data;
      bufferSize = (int)fileLength;
      dataIn.reset(buffer, start, length);
    }
    
    @Override
    public long getPosition() throws IOException {
      // InMemoryReader does not initialize streams like Reader, so in.getPos()
      // would not work. Instead, return the number of uncompressed bytes read,
      // which will be correct since in-memory data is not compressed.
      return bytesRead;
    }
    
    @Override
    public long getLength() { 
      return fileLength;
    }
    
    private void dumpOnError() {
      File dumpFile = new File("../output/" + taskid + ".dump");
      System.err.println("Dumping corrupt map-output of " + taskid + 
                         " to " + dumpFile.getAbsolutePath());
      try {
        FileOutputStream fos = new FileOutputStream(dumpFile);
        fos.write(buffer, 0, bufferSize);
        fos.close();
      } catch (IOException ioe) {
        System.err.println("Failed to dump map-output of " + taskid);
      }
    }
    
    public boolean next(DataInputBuffer key, DataInputBuffer value) 
    throws IOException {
      try {
      // Sanity check
      if (eof) {
        throw new EOFException("Completed reading " + bytesRead);
      }
      
      // Read key and value lengths
      int oldPos = dataIn.getPosition();
      int keyLength = WritableUtils.readVInt(dataIn);
      int valueLength = WritableUtils.readVInt(dataIn);
      int pos = dataIn.getPosition();
      bytesRead += pos - oldPos;
      
      // Check for EOF
      if (keyLength == EOF_MARKER && valueLength == EOF_MARKER) {
        eof = true;
        return false;
      }
      
      // Sanity check
      if (keyLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
                              keyLength);
      }
      if (valueLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative value-length: " + 
                              valueLength);
      }

      final int recordLength = keyLength + valueLength;
      
      // Setup the key and value
      pos = dataIn.getPosition();
      byte[] data = dataIn.getData();
      key.reset(data, pos, keyLength);
      value.reset(data, (pos + keyLength), valueLength);
      
      // Position for the next record
      long skipped = dataIn.skip(recordLength);
      if (skipped != recordLength) {
        throw new IOException("Rec# " + recNo + ": Failed to skip past record of length: " + 
                              recordLength);
      }
      
      // Record the byte
      bytesRead += recordLength;

      ++recNo;
      
      return true;
      } catch (IOException ioe) {
        dumpOnError();
        throw ioe;
      }
    }
      
    public void close() {
      // Release
      dataIn = null;
      buffer = null;
      
      // Inform the RamManager
      ramManager.unreserve(bufferSize);
    }
  }
  
  public static class StateDataReader<K extends Object, V extends Object> {
	    private static final int DEFAULT_BUFFER_SIZE = 128*1024;
	    private static final int MAX_VINT_SIZE = 9;

	    // Count records read from disk
	    private long numRecordsRead = 0;
	    private final Counters.Counter readRecordsCounter;

	    final InputStream in;        // Possibly decompressed stream that we read
	    Decompressor decompressor;
	    protected long bytesRead = 0;
	    final long fileLength;
	    boolean eof = false;
	    final IFileInputStream checksumIn;
	    
	    byte[] buffer = null;
	    int bufferSize = DEFAULT_BUFFER_SIZE;
	    DataInputBuffer dataIn = new DataInputBuffer();

	    int recNo = 1;
	    
	    /**
	     * Construct an IFile Reader.
	     * 
	     * @param conf Configuration File 
	     * @param fs  FileSystem
	     * @param file Path of the file to be opened. This file should have
	     *             checksum bytes for the data at the end of the file.
	     * @param codec codec
	     * @param readsCounter Counter for records read from disk
	     * @throws IOException
	     */
	    public StateDataReader(Configuration conf, FileSystem fs, Path file,
	                  CompressionCodec codec,
	                  Counters.Counter readsCounter) throws IOException {
	      this(conf, fs.open(file), 
	           fs.getFileStatus(file).getLen(),
	           codec, readsCounter);
	    }

	    /**
	     * Construct an IFile Reader.
	     * 
	     * @param conf Configuration File 
	     * @param in   The input stream
	     * @param length Length of the data in the stream, including the checksum
	     *               bytes.
	     * @param codec codec
	     * @param readsCounter Counter for records read from disk
	     * @throws IOException
	     */
	    public StateDataReader(Configuration conf, DataInputStream in, long length, 
	                  CompressionCodec codec,
	                  Counters.Counter readsCounter) throws IOException {
	      readRecordsCounter = readsCounter;
	      checksumIn = new IFileInputStream(in,length);
	      if (codec != null) {
	        decompressor = CodecPool.getDecompressor(codec);
	        this.in = codec.createInputStream(checksumIn, decompressor);
	      } else {
	        this.in = checksumIn;
	      }
	      this.fileLength = length;
	      
	      if (conf != null) {
	        bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
	      }
	    }
	    
	    public long getLength() { 
	      return fileLength - checksumIn.getSize();
	    }
	    
	    public long getPosition() throws IOException {    
	      return checksumIn.getPosition(); 
	    }
	    
	    /**
	     * Read upto len bytes into buf starting at offset off.
	     * 
	     * @param buf buffer 
	     * @param off offset
	     * @param len length of buffer
	     * @return the no. of bytes read
	     * @throws IOException
	     */
	    private int readData(byte[] buf, int off, int len) throws IOException {
	      int bytesRead = 0;
	      while (bytesRead < len) {
	        int n = in.read(buf, off+bytesRead, len-bytesRead);
	        if (n < 0) {
	          return bytesRead;
	        }
	        bytesRead += n;
	      }
	      return len;
	    }
	    
	    void readNextBlock(int minSize) throws IOException {
	      if (buffer == null) {
	        buffer = new byte[bufferSize];
	        dataIn.reset(buffer, 0, 0);
	      }
	      buffer = 
	        rejigData(buffer, 
	                  (bufferSize < minSize) ? new byte[minSize << 1] : buffer);
	      bufferSize = buffer.length;
	    }
	    
	    private byte[] rejigData(byte[] source, byte[] destination) 
	    throws IOException{
	      // Copy remaining data into the destination array
	      int bytesRemaining = dataIn.getLength()-dataIn.getPosition();
	      if (bytesRemaining > 0) {
	        System.arraycopy(source, dataIn.getPosition(), 
	            destination, 0, bytesRemaining);
	      }
	      
	      // Read as much data as will fit from the underlying stream 
	      int n = readData(destination, bytesRemaining, 
	                       (destination.length - bytesRemaining));
	      dataIn.reset(destination, 0, (bytesRemaining + n));
	      
	      return destination;
	    }
	    
	    public boolean next(DataInputBuffer key, DataInputBuffer iState, DataInputBuffer cState) 
	    throws IOException {
	      // Sanity check
	      if (eof) {
	        throw new EOFException("Completed reading " + bytesRead);
	      }
	      
	      // Check if we have enough data to read lengths
	      if ((dataIn.getLength() - dataIn.getPosition()) < 3*MAX_VINT_SIZE) {
	        readNextBlock(3*MAX_VINT_SIZE);
	      }
	      
	      // Read key and value lengths
	      int oldPos = dataIn.getPosition();
	      int keyLength = WritableUtils.readVInt(dataIn);
	      int iStateLength = WritableUtils.readVInt(dataIn);
	      int cStateLength = WritableUtils.readVInt(dataIn);
	      int pos = dataIn.getPosition();
	      bytesRead += pos - oldPos;
	      
	      // Check for EOF
	      if (keyLength == EOF_MARKER && iStateLength == EOF_MARKER && cStateLength == EOF_MARKER) {
	        eof = true;
	        return false;
	      }
	      
	      // Sanity check
	      if (keyLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
	                              keyLength);
	      }
	      if (iStateLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative cState-length: " + 
	        						iStateLength);
	      }
	      if (cStateLength < 0) {
		        throw new IOException("Rec# " + recNo + ": Negative cState-length: " + 
		        						cStateLength);
		  }
	      
	      final int recordLength = keyLength + iStateLength + cStateLength;
	      
	      // Check if we have the raw key/value in the buffer
	      if ((dataIn.getLength()-pos) < recordLength) {
	        readNextBlock(recordLength);
	        
	        // Sanity check
	        if ((dataIn.getLength() - dataIn.getPosition()) < recordLength) {
	          throw new EOFException("Rec# " + recNo + ": Could read the next " +
	          		                   " record");
	        }
	      }

	      // Setup the key and value
	      pos = dataIn.getPosition();
	      byte[] data = dataIn.getData();
	      key.reset(data, pos, keyLength);
	      iState.reset(data, (pos + keyLength), iStateLength);
	      cState.reset(data, (pos + keyLength + iStateLength), cStateLength);
	      
	      // Position for the next record
	      long skipped = dataIn.skip(recordLength);
	      if (skipped != recordLength) {
	        throw new IOException("Rec# " + recNo + ": Failed to skip past record " +
	        		                  "of length: " + recordLength);
	      }
	      
	      // Record the bytes read
	      bytesRead += recordLength;

	      ++recNo;
	      ++numRecordsRead;

	      return true;
	    }
	    
	    public void close() throws IOException {
	      // Return the decompressor
	      if (decompressor != null) {
	        decompressor.reset();
	        CodecPool.returnDecompressor(decompressor);
	        decompressor = null;
	      }
	      
	      // Close the underlying stream
	      in.close();
	      
	      // Release the buffer
	      dataIn = null;
	      buffer = null;
	      if(readRecordsCounter != null) {
	        readRecordsCounter.increment(numRecordsRead);
	      }
	    }
	  } 
  
  public static class StaticDataReader<K extends Object, D extends Object> {
	    private static final int DEFAULT_BUFFER_SIZE = 128*1024;
	    private static final int MAX_VINT_SIZE = 9;

	    // Count records read from disk
	    private long numRecordsRead = 0;
	    private final Counters.Counter readRecordsCounter;

	    final InputStream in;        // Possibly decompressed stream that we read
	    Decompressor decompressor;
	    protected long bytesRead = 0;
	    final long fileLength;
	    boolean eof = false;
	    final IFileInputStream checksumIn;
	    
	    byte[] buffer = null;
	    int bufferSize = DEFAULT_BUFFER_SIZE;
	    DataInputBuffer dataIn = new DataInputBuffer();

	    int recNo = 1;
	    
	    /**
	     * Construct an IFile Reader.
	     * 
	     * @param conf Configuration File 
	     * @param fs  FileSystem
	     * @param file Path of the file to be opened. This file should have
	     *             checksum bytes for the data at the end of the file.
	     * @param codec codec
	     * @param readsCounter Counter for records read from disk
	     * @throws IOException
	     */
	    public StaticDataReader(Configuration conf, FileSystem fs, Path file,
	                  CompressionCodec codec,
	                  Counters.Counter readsCounter) throws IOException {
	      this(conf, fs.open(file), 
	           fs.getFileStatus(file).getLen(),
	           codec, readsCounter);
	    }

	    /**
	     * Construct an IFile Reader.
	     * 
	     * @param conf Configuration File 
	     * @param in   The input stream
	     * @param length Length of the data in the stream, including the checksum
	     *               bytes.
	     * @param codec codec
	     * @param readsCounter Counter for records read from disk
	     * @throws IOException
	     */
	    public StaticDataReader(Configuration conf, DataInputStream in, long length, 
	                  CompressionCodec codec,
	                  Counters.Counter readsCounter) throws IOException {
	      readRecordsCounter = readsCounter;
	      checksumIn = new IFileInputStream(in,length);
	      if (codec != null) {
	        decompressor = CodecPool.getDecompressor(codec);
	        this.in = codec.createInputStream(checksumIn, decompressor);
	      } else {
	        this.in = checksumIn;
	      }
	      this.fileLength = length;
	      
	      if (conf != null) {
	        bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
	      }
	    }
	    
	    public long getLength() { 
	      return fileLength - checksumIn.getSize();
	    }
	    
	    public long getPosition() throws IOException {    
	      return checksumIn.getPosition(); 
	    }
	    
	    /**
	     * Read upto len bytes into buf starting at offset off.
	     * 
	     * @param buf buffer 
	     * @param off offset
	     * @param len length of buffer
	     * @return the no. of bytes read
	     * @throws IOException
	     */
	    private int readData(byte[] buf, int off, int len) throws IOException {
	      int bytesRead = 0;
	      while (bytesRead < len) {
	        int n = in.read(buf, off+bytesRead, len-bytesRead);
	        if (n < 0) {
	          return bytesRead;
	        }
	        bytesRead += n;
	      }
	      return len;
	    }
	    
	    void readNextBlock(int minSize) throws IOException {
	      if (buffer == null) {
	        buffer = new byte[bufferSize];
	        dataIn.reset(buffer, 0, 0);
	      }
	      buffer = 
	        rejigData(buffer, 
	                  (bufferSize < minSize) ? new byte[minSize << 1] : buffer);
	      bufferSize = buffer.length;
	    }
	    
	    private byte[] rejigData(byte[] source, byte[] destination) 
	    throws IOException{
	      // Copy remaining data into the destination array
	      int bytesRemaining = dataIn.getLength()-dataIn.getPosition();
	      if (bytesRemaining > 0) {
	        System.arraycopy(source, dataIn.getPosition(), 
	            destination, 0, bytesRemaining);
	      }
	      
	      // Read as much data as will fit from the underlying stream 
	      int n = readData(destination, bytesRemaining, 
	                       (destination.length - bytesRemaining));
	      dataIn.reset(destination, 0, (bytesRemaining + n));
	      
	      return destination;
	    }
	    
	    public boolean next(DataInputBuffer key, DataInputBuffer staticdata) 
	    throws IOException {
	      // Sanity check
	      if (eof) {
	        throw new EOFException("Completed reading " + bytesRead);
	      }
	      
	      // Check if we have enough data to read lengths
	      if ((dataIn.getLength() - dataIn.getPosition()) < 2*MAX_VINT_SIZE) {
	        readNextBlock(2*MAX_VINT_SIZE);
	      }
	      
	      // Read key and value lengths
	      int oldPos = dataIn.getPosition();
	      int keyLength = WritableUtils.readVInt(dataIn);
	      int dataLength = WritableUtils.readVInt(dataIn);
	      int pos = dataIn.getPosition();
	      bytesRead += pos - oldPos;
	      
	      // Check for EOF
	      if (keyLength == EOF_MARKER && dataLength == EOF_MARKER) {
	        eof = true;
	        return false;
	      }
	      
	      // Sanity check
	      if (keyLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
	                              keyLength);
	      }
	      if (dataLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative cState-length: " + 
	        						dataLength);
	      }
	      
	      final int recordLength = keyLength + dataLength;
	      
	      // Check if we have the raw key/value in the buffer
	      if ((dataIn.getLength()-pos) < recordLength) {
	        readNextBlock(recordLength);
	        
	        // Sanity check
	        if ((dataIn.getLength() - dataIn.getPosition()) < recordLength) {
	          throw new EOFException("Rec# " + recNo + ": Could read the next " +
	          		                   " record");
	        }
	      }

	      // Setup the key and value
	      pos = dataIn.getPosition();
	      byte[] data = dataIn.getData();
	      key.reset(data, pos, keyLength);
	      staticdata.reset(data, (pos + keyLength), dataLength);
	      
	      // Position for the next record
	      long skipped = dataIn.skip(recordLength);
	      if (skipped != recordLength) {
	        throw new IOException("Rec# " + recNo + ": Failed to skip past record " +
	        		                  "of length: " + recordLength);
	      }
	      
	      // Record the bytes read
	      bytesRead += recordLength;

	      ++recNo;
	      ++numRecordsRead;

	      return true;
	    }
	    
	    public void close() throws IOException {
	      // Return the decompressor
	      if (decompressor != null) {
	        decompressor.reset();
	        CodecPool.returnDecompressor(decompressor);
	        decompressor = null;
	      }
	      
	      // Close the underlying stream
	      in.close();
	      
	      // Release the buffer
	      dataIn = null;
	      buffer = null;
	      if(readRecordsCounter != null) {
	        readRecordsCounter.increment(numRecordsRead);
	      }
	    }
	  } 
  
  public static class PriorityQueueReader<K extends Object, V extends Object, D extends Object> {
	    private static final int DEFAULT_BUFFER_SIZE = 128*1024;
	    private static final int MAX_VINT_SIZE = 9;

	    // Count records read from disk
	    private long numRecordsRead = 0;
	    private final Counters.Counter readRecordsCounter;

	    final InputStream in;        // Possibly decompressed stream that we read
	    Decompressor decompressor;
	    protected long bytesRead = 0;
	    final long fileLength;
	    boolean eof = false;
	    final IFileInputStream checksumIn;
	    
	    byte[] buffer = null;
	    int bufferSize = DEFAULT_BUFFER_SIZE;
	    DataInputBuffer dataIn = new DataInputBuffer();

	    int recNo = 1;
	    
	    /**
	     * Construct an IFile Reader.
	     * 
	     * @param conf Configuration File 
	     * @param fs  FileSystem
	     * @param file Path of the file to be opened. This file should have
	     *             checksum bytes for the data at the end of the file.
	     * @param codec codec
	     * @param readsCounter Counter for records read from disk
	     * @throws IOException
	     */
	    public PriorityQueueReader(Configuration conf, FileSystem fs, Path file,
	                  CompressionCodec codec,
	                  Counters.Counter readsCounter) throws IOException {
	      this(conf, fs.open(file), 
	           fs.getFileStatus(file).getLen(),
	           codec, readsCounter);
	    }

	    /**
	     * Construct an IFile Reader.
	     * 
	     * @param conf Configuration File 
	     * @param in   The input stream
	     * @param length Length of the data in the stream, including the checksum
	     *               bytes.
	     * @param codec codec
	     * @param readsCounter Counter for records read from disk
	     * @throws IOException
	     */
	    public PriorityQueueReader(Configuration conf, DataInputStream in, long length, 
	                  CompressionCodec codec,
	                  Counters.Counter readsCounter) throws IOException {
	      readRecordsCounter = readsCounter;
	      checksumIn = new IFileInputStream(in,length);
	      if (codec != null) {
	        decompressor = CodecPool.getDecompressor(codec);
	        this.in = codec.createInputStream(checksumIn, decompressor);
	      } else {
	        this.in = checksumIn;
	      }
	      this.fileLength = length;
	      
	      if (conf != null) {
	        bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
	      }
	    }
	    
	    public long getLength() { 
	      return fileLength - checksumIn.getSize();
	    }
	    
	    public long getPosition() throws IOException {    
	      return checksumIn.getPosition(); 
	    }
	    
	    /**
	     * Read upto len bytes into buf starting at offset off.
	     * 
	     * @param buf buffer 
	     * @param off offset
	     * @param len length of buffer
	     * @return the no. of bytes read
	     * @throws IOException
	     */
	    private int readData(byte[] buf, int off, int len) throws IOException {
	      int bytesRead = 0;
	      while (bytesRead < len) {
	        int n = in.read(buf, off+bytesRead, len-bytesRead);
	        if (n < 0) {
	          return bytesRead;
	        }
	        bytesRead += n;
	      }
	      return len;
	    }
	    
	    void readNextBlock(int minSize) throws IOException {
	      if (buffer == null) {
	        buffer = new byte[bufferSize];
	        dataIn.reset(buffer, 0, 0);
	      }
	      buffer = 
	        rejigData(buffer, 
	                  (bufferSize < minSize) ? new byte[minSize << 1] : buffer);
	      bufferSize = buffer.length;
	    }
	    
	    private byte[] rejigData(byte[] source, byte[] destination) 
	    throws IOException{
	      // Copy remaining data into the destination array
	      int bytesRemaining = dataIn.getLength()-dataIn.getPosition();
	      if (bytesRemaining > 0) {
	        System.arraycopy(source, dataIn.getPosition(), 
	            destination, 0, bytesRemaining);
	      }
	      
	      // Read as much data as will fit from the underlying stream 
	      int n = readData(destination, bytesRemaining, 
	                       (destination.length - bytesRemaining));
	      dataIn.reset(destination, 0, (bytesRemaining + n));
	      
	      return destination;
	    }
	    
	    public boolean next(DataInputBuffer key, DataInputBuffer istate, DataInputBuffer staticdata) 
	    throws IOException {
	      // Sanity check
	      if (eof) {
	        throw new EOFException("Completed reading " + bytesRead);
	      }
	      
	      // Check if we have enough data to read lengths
	      if ((dataIn.getLength() - dataIn.getPosition()) < 3*MAX_VINT_SIZE) {
	        readNextBlock(3*MAX_VINT_SIZE);
	      }
	      
	      // Read key and value lengths
	      int oldPos = dataIn.getPosition();
	      int keyLength = WritableUtils.readVInt(dataIn);
	      int istateLength = WritableUtils.readVInt(dataIn);
	      int dataLength = WritableUtils.readVInt(dataIn);
	      int pos = dataIn.getPosition();
	      bytesRead += pos - oldPos;
	      
	      // Check for EOF
	      if (keyLength == EOF_MARKER && istateLength == EOF_MARKER && dataLength == EOF_MARKER) {
	        eof = true;
	        return false;
	      }
	      
	      // Sanity check
	      if (keyLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
	                              keyLength);
	      }
	      if (istateLength < 0) {
		        throw new IOException("Rec# " + recNo + ": Negative iState-length: " + 
		        		istateLength);
		      }
	      if (dataLength < 0) {
	        throw new IOException("Rec# " + recNo + ": Negative data-length: " + 
	        						dataLength);
	      }
	      
	      final int recordLength = keyLength + istateLength + dataLength;
	      
	      // Check if we have the raw key/value in the buffer
	      if ((dataIn.getLength()-pos) < recordLength) {
	        readNextBlock(recordLength);
	        
	        // Sanity check
	        if ((dataIn.getLength() - dataIn.getPosition()) < recordLength) {
	          throw new EOFException("Rec# " + recNo + ": Could read the next " +
	          		                   " record");
	        }
	      }

	      // Setup the key and value
	      pos = dataIn.getPosition();
	      byte[] data = dataIn.getData();
	      key.reset(data, pos, keyLength);
	      istate.reset(data, (pos + keyLength), istateLength);
	      staticdata.reset(data, (pos + keyLength + istateLength), dataLength);
	      
	      // Position for the next record
	      long skipped = dataIn.skip(recordLength);
	      if (skipped != recordLength) {
	        throw new IOException("Rec# " + recNo + ": Failed to skip past record " +
	        		                  "of length: " + recordLength);
	      }
	      
	      // Record the bytes read
	      bytesRead += recordLength;

	      ++recNo;
	      ++numRecordsRead;

	      return true;
	    }
	    
	    public void close() throws IOException {
	      // Return the decompressor
	      if (decompressor != null) {
	        decompressor.reset();
	        CodecPool.returnDecompressor(decompressor);
	        decompressor = null;
	      }
	      
	      // Close the underlying stream
	      in.close();
	      
	      // Release the buffer
	      dataIn = null;
	      buffer = null;
	      if(readRecordsCounter != null) {
	        readRecordsCounter.increment(numRecordsRead);
	      }
	    }
	  } 
}
