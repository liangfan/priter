package org.apache.hadoop.mapred;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.WritableComparable;

public class IntWritableIncreasingComparator extends IntWritable.Comparator {
	public int compare(WritableComparable a, WritableComparable b) {
		return super.compare(a,  b);
	}
	
    public int compare(byte[] b1, int s1, int l1,
            byte[] b2, int s2, int l2) {
		return super.compare(b1,  s1, l1, b2, s2, l2);
	}
}
