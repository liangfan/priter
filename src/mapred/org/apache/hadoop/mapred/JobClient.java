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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import javax.security.auth.login.LoginException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.mapred.Counters.Counter;
import org.apache.hadoop.mapred.Counters.Group;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UnixUserGroupInformation;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * <code>JobClient</code> is the primary interface for the user-job to interact
 * with the {@link JobTracker}.
 * 
 * <code>JobClient</code> provides facilities to submit jobs, track their 
 * progress, access component-tasks' reports/logs, get the Map-Reduce cluster
 * status information etc.
 * 
 * <p>The job submission process involves:
 * <ol>
 *   <li>
 *   Checking the input and output specifications of the job.
 *   </li>
 *   <li>
 *   Computing the {@link InputSplit}s for the job.
 *   </li>
 *   <li>
 *   Setup the requisite accounting information for the {@link DistributedCache} 
 *   of the job, if necessary.
 *   </li>
 *   <li>
 *   Copying the job's jar and configuration to the map-reduce system directory 
 *   on the distributed file-system. 
 *   </li>
 *   <li>
 *   Submitting the job to the <code>JobTracker</code> and optionally monitoring
 *   it's status.
 *   </li>
 * </ol></p>
 *  
 * Normally the user creates the application, describes various facets of the
 * job via {@link JobConf} and then uses the <code>JobClient</code> to submit 
 * the job and monitor its progress.
 * 
 * <p>Here is an example on how to use <code>JobClient</code>:</p>
 * <p><blockquote><pre>
 *     // Create a new JobConf
 *     JobConf job = new JobConf(new Configuration(), MyJob.class);
 *     
 *     // Specify various job-specific parameters     
 *     job.setJobName("myjob");
 *     
 *     job.setInputPath(new Path("in"));
 *     job.setOutputPath(new Path("out"));
 *     
 *     job.setMapperClass(MyJob.MyMapper.class);
 *     job.setReducerClass(MyJob.MyReducer.class);
 *
 *     // Submit the job, then poll for progress until the job is complete
 *     JobClient.runJob(job);
 * </pre></blockquote></p>
 * 
 * <h4 id="JobControl">Job Control</h4>
 * 
 * <p>At times clients would chain map-reduce jobs to accomplish complex tasks 
 * which cannot be done via a single map-reduce job. This is fairly easy since 
 * the output of the job, typically, goes to distributed file-system and that 
 * can be used as the input for the next job.</p>
 * 
 * <p>However, this also means that the onus on ensuring jobs are complete 
 * (success/failure) lies squarely on the clients. In such situations the 
 * various job-control options are:
 * <ol>
 *   <li>
 *   {@link #runJob(JobConf)} : submits the job and returns only after 
 *   the job has completed.
 *   </li>
 *   <li>
 *   {@link #submitJob(JobConf)} : only submits the job, then poll the 
 *   returned handle to the {@link RunningJob} to query status and make 
 *   scheduling decisions.
 *   </li>
 *   <li>
 *   {@link JobConf#setJobEndNotificationURI(String)} : setup a notification
 *   on job-completion, thus avoiding polling.
 *   </li>
 * </ol></p>
 * 
 * @see JobConf
 * @see ClusterStatus
 * @see Tool
 * @see DistributedCache
 */
public class JobClient extends Configured implements MRConstants, Tool  {
  private static final Log LOG = LogFactory.getLog(JobClient.class);
  public static enum TaskStatusFilter { NONE, KILLED, FAILED, SUCCEEDED, ALL }
  private TaskStatusFilter taskOutputFilter = TaskStatusFilter.FAILED; 
  private static Configuration commandLineConfig;
  static long MAX_JOBPROFILE_AGE = 1000 * 2;

  /**
   * A NetworkedJob is an implementation of RunningJob.  It holds
   * a JobProfile object to provide some info, and interacts with the
   * remote service to provide certain functionality.
   */
  class NetworkedJob implements RunningJob {
    JobProfile profile;
    JobStatus status;
    long statustime;

    /**
     * We store a JobProfile and a timestamp for when we last
     * acquired the job profile.  If the job is null, then we cannot
     * perform any of the tasks.  The job might be null if the JobTracker
     * has completely forgotten about the job.  (eg, 24 hours after the
     * job completes.)
     */
    public NetworkedJob(JobStatus job) throws IOException {
      this.status = job;
      this.profile = jobSubmitClient.getJobProfile(job.getJobID());
      this.statustime = System.currentTimeMillis();
    }

    /**
     * Some methods rely on having a recent job profile object.  Refresh
     * it, if necessary
     */
    synchronized void ensureFreshStatus() throws IOException {
      if (System.currentTimeMillis() - statustime > MAX_JOBPROFILE_AGE) {
        updateStatus();
      }
    }
    
    /** Some methods need to update status immediately. So, refresh
     * immediately
     * @throws IOException
     */
    synchronized void updateStatus() throws IOException {
      this.status = jobSubmitClient.getJobStatus(profile.getJobID());
      this.statustime = System.currentTimeMillis();
    }

    /**
     * An identifier for the job
     */
    public JobID getID() {
      return profile.getJobID();
    }
    
    /** @deprecated This method is deprecated and will be removed. Applications should 
     * rather use {@link #getID()}.*/
    @Deprecated
    public String getJobID() {
      return profile.getJobID().toString();
    }
    
    /**
     * The user-specified job name
     */
    public String getJobName() {
      return profile.getJobName();
    }

    /**
     * The name of the job file
     */
    public String getJobFile() {
      return profile.getJobFile();
    }

    /**
     * A URL where the job's status can be seen
     */
    public String getTrackingURL() {
      return profile.getURL().toString();
    }

    /**
     * A float between 0.0 and 1.0, indicating the % of map work
     * completed.
     */
    public float mapProgress() throws IOException {
      ensureFreshStatus();
      return status.mapProgress();
    }

    /**
     * A float between 0.0 and 1.0, indicating the % of reduce work
     * completed.
     */
    public float reduceProgress() throws IOException {
      ensureFreshStatus();
      return status.reduceProgress();
    }

    /**
     * A float between 0.0 and 1.0, indicating the % of cleanup work
     * completed.
     */
    public float cleanupProgress() throws IOException {
      ensureFreshStatus();
      return status.cleanupProgress();
    }

    /**
     * A float between 0.0 and 1.0, indicating the % of setup work
     * completed.
     */
    public float setupProgress() throws IOException {
      ensureFreshStatus();
      return status.setupProgress();
    }

    /**
     * Returns immediately whether the whole job is done yet or not.
     */
    public synchronized boolean isComplete() throws IOException {
      updateStatus();
      return (status.getRunState() == JobStatus.SUCCEEDED ||
              status.getRunState() == JobStatus.FAILED ||
              status.getRunState() == JobStatus.KILLED);
    }

    /**
     * True iff job completed successfully.
     */
    public synchronized boolean isSuccessful() throws IOException {
      updateStatus();
      return status.getRunState() == JobStatus.SUCCEEDED;
    }

    /**
     * Blocks until the job is finished
     */
    public void waitForCompletion() throws IOException {
      while (!isComplete()) {
        try {
          Thread.sleep(5000);
        } catch (InterruptedException ie) {
        }
      }
    }

    /**
     * Tells the service to get the state of the current job.
     */
    public synchronized int getJobState() throws IOException {
      updateStatus();
      return status.getRunState();
    }
    
    /**
     * Tells the service to terminate the current job.
     */
    public synchronized void killJob() throws IOException {
      jobSubmitClient.killJob(getID());
    }
   
    
    /** Set the priority of the job.
    * @param priority new priority of the job. 
    */
    public synchronized void setJobPriority(String priority) 
                                                throws IOException {
      jobSubmitClient.setJobPriority(getID(), priority);
    }
    
    /**
     * Kill indicated task attempt.
     * @param taskId the id of the task to kill.
     * @param shouldFail if true the task is failed and added to failed tasks list, otherwise
     * it is just killed, w/o affecting job failure status.
     */
    public synchronized void killTask(TaskAttemptID taskId, boolean shouldFail) throws IOException {
      jobSubmitClient.killTask(taskId, shouldFail);
    }

    /** @deprecated Applications should rather use {@link #killTask(TaskAttemptID, boolean)}*/
    @Deprecated
    public synchronized void killTask(String taskId, boolean shouldFail) throws IOException {
      killTask(TaskAttemptID.forName(taskId), shouldFail);
    }
    
    /**
     * Fetch task completion events from jobtracker for this job. 
     */
    public synchronized TaskCompletionEvent[] getTaskCompletionEvents(
                                                                      int startFrom) throws IOException{
      return jobSubmitClient.getTaskCompletionEvents(
                                                     getID(), startFrom, 10); 
    }

    /**
     * Dump stats to screen
     */
    @Override
    public String toString() {
      try {
        updateStatus();
      } catch (IOException e) {
      }
      return "Job: " + profile.getJobID() + "\n" + 
        "file: " + profile.getJobFile() + "\n" + 
        "tracking URL: " + profile.getURL() + "\n" + 
        "map() completion: " + status.mapProgress() + "\n" + 
        "reduce() completion: " + status.reduceProgress();
    }
        
    /**
     * Returns the counters for this job
     */
    public Counters getCounters() throws IOException {
      return jobSubmitClient.getJobCounters(getID());
    }
  }

  JobSubmissionProtocol jobSubmitClient;
  Path sysDir = null;
  
  FileSystem fs = null;

  static Random r = new Random();

  /**
   * Create a job client.
   */
  public JobClient() {
  }
    
  /**
   * Build a job client with the given {@link JobConf}, and connect to the 
   * default {@link JobTracker}.
   * 
   * @param conf the job configuration.
   * @throws IOException
   */
  public JobClient(JobConf conf) throws IOException {
    setConf(conf);
    init(conf);
  }

  /**
   * set the command line config in the jobclient. these are
   * parameters paassed from the command line and stored in 
   * conf
   * @param conf the configuration object to set.
   */
  static synchronized void  setCommandLineConfig(Configuration conf) {
    commandLineConfig = conf;
  }
  
  /**
   * return the command line configuration
   */
  public static synchronized Configuration getCommandLineConfig() {
    return commandLineConfig;
  }
  
 
  /**
   * Connect to the default {@link JobTracker}.
   * @param conf the job configuration.
   * @throws IOException
   */
  public void init(JobConf conf) throws IOException {
    String tracker = conf.get("mapred.job.tracker", "local");
    if ("local".equals(tracker)) {
      this.jobSubmitClient = new LocalJobRunner(conf);
    } else {
      this.jobSubmitClient = createRPCProxy(JobTracker.getAddress(conf), conf);
    }        
  }

  private JobSubmissionProtocol createRPCProxy(InetSocketAddress addr,
      Configuration conf) throws IOException {
    return (JobSubmissionProtocol) RPC.getProxy(JobSubmissionProtocol.class,
        JobSubmissionProtocol.versionID, addr, getUGI(conf), conf,
        NetUtils.getSocketFactory(conf, JobSubmissionProtocol.class));
  }

  /**
   * Build a job client, connect to the indicated job tracker.
   * 
   * @param jobTrackAddr the job tracker to connect to.
   * @param conf configuration.
   */
  public JobClient(InetSocketAddress jobTrackAddr, 
                   Configuration conf) throws IOException {
    jobSubmitClient = createRPCProxy(jobTrackAddr, conf);
  }

  /**
   * Close the <code>JobClient</code>.
   */
  public synchronized void close() throws IOException {
    if (!(jobSubmitClient instanceof LocalJobRunner)) {
      RPC.stopProxy(jobSubmitClient);
    }
  }

  /**
   * Get a filesystem handle.  We need this to prepare jobs
   * for submission to the MapReduce system.
   * 
   * @return the filesystem handle.
   */
  public synchronized FileSystem getFs() throws IOException {
    if (this.fs == null) {
      Path sysDir = getSystemDir();
      this.fs = sysDir.getFileSystem(getConf());
    }
    return fs;
  }
  
  /* see if two file systems are the same or not
   *
   */
  private boolean compareFs(FileSystem srcFs, FileSystem destFs) {
    URI srcUri = srcFs.getUri();
    URI dstUri = destFs.getUri();
    if (srcUri.getScheme() == null) {
      return false;
    }
    if (!srcUri.getScheme().equals(dstUri.getScheme())) {
      return false;
    }
    String srcHost = srcUri.getHost();    
    String dstHost = dstUri.getHost();
    if ((srcHost != null) && (dstHost != null)) {
      try {
        srcHost = InetAddress.getByName(srcHost).getCanonicalHostName();
        dstHost = InetAddress.getByName(dstHost).getCanonicalHostName();
      } catch(UnknownHostException ue) {
        return false;
      }
      if (!srcHost.equals(dstHost)) {
        return false;
      }
    }
    else if (srcHost == null && dstHost != null) {
      return false;
    }
    else if (srcHost != null && dstHost == null) {
      return false;
    }
    //check for ports
    if (srcUri.getPort() != dstUri.getPort()) {
      return false;
    }
    return true;
  }

  // copies a file to the jobtracker filesystem and returns the path where it
  // was copied to
  private Path copyRemoteFiles(FileSystem jtFs, Path parentDir, Path originalPath, 
                               JobConf job, short replication) throws IOException {
    //check if we do not need to copy the files
    // is jt using the same file system.
    // just checking for uri strings... doing no dns lookups 
    // to see if the filesystems are the same. This is not optimal.
    // but avoids name resolution.
    
    FileSystem remoteFs = null;
    remoteFs = originalPath.getFileSystem(job);
    if (compareFs(remoteFs, jtFs)) {
      return originalPath;
    }
    // this might have name collisions. copy will throw an exception
    //parse the original path to create new path
    Path newPath = new Path(parentDir, originalPath.getName());
    FileUtil.copy(remoteFs, originalPath, jtFs, newPath, false, job);
    jtFs.setReplication(newPath, replication);
    return newPath;
  }
 
  /**
   * configure the jobconf of the user with the command line options of 
   * -libjars, -files, -archives
   * @param conf
   * @throws IOException
   */
  private void configureCommandLineOptions(JobConf job, Path submitJobDir, Path submitJarFile) 
    throws IOException {
    
    final String warning = "Use genericOptions for the option ";

    if (!(job.getBoolean("mapred.used.genericoptionsparser", false))) {
      LOG.warn("Use GenericOptionsParser for parsing the arguments. " +
               "Applications should implement Tool for the same.");
    }

    // get all the command line arguments into the 
    // jobconf passed in by the user conf
    Configuration commandConf = JobClient.getCommandLineConfig();
    String files = null;
    String libjars = null;
    String archives = null;

    files = job.get("tmpfiles");
    if (files == null) {
      if (commandConf != null) {
        files = commandConf.get("tmpfiles");
        if (files != null) {
          LOG.warn(warning + "-files");
        }
      }
    }

    libjars = job.get("tmpjars");
    if (libjars == null) {
      if (commandConf != null) {
        libjars = commandConf.get("tmpjars");
        if (libjars != null) {
          LOG.warn(warning + "-libjars");
        }
      }
    }

    archives = job.get("tmparchives");
    if (archives == null) {
      if (commandConf != null) {
        archives = commandConf.get("tmparchives");
        if (archives != null) {
          LOG.warn(warning + "-archives");
        }
      }
    }
    
    /*
     * set this user's id in job configuration, so later job files can be
     * accessed using this user's id
     */
    UnixUserGroupInformation ugi = getUGI(job);
      
    //
    // Figure out what fs the JobTracker is using.  Copy the
    // job to it, under a temporary name.  This allows DFS to work,
    // and under the local fs also provides UNIX-like object loading 
    // semantics.  (that is, if the job file is deleted right after
    // submission, we can still run the submission to completion)
    //

    // Create a number of filenames in the JobTracker's fs namespace
    FileSystem fs = getFs();
    LOG.debug("default FileSystem: " + fs.getUri());
    fs.delete(submitJobDir, true);
    submitJobDir = fs.makeQualified(submitJobDir);
    submitJobDir = new Path(submitJobDir.toUri().getPath());
    FsPermission mapredSysPerms = new FsPermission(JOB_DIR_PERMISSION);
    FileSystem.mkdirs(fs, submitJobDir, mapredSysPerms);
    Path filesDir = new Path(submitJobDir, "files");
    Path archivesDir = new Path(submitJobDir, "archives");
    Path libjarsDir = new Path(submitJobDir, "libjars");
    short replication = (short)job.getInt("mapred.submit.replication", 10);
    // add all the command line files/ jars and archive
    // first copy them to jobtrackers filesystem 
    
    if (files != null) {
      FileSystem.mkdirs(fs, filesDir, mapredSysPerms);
      String[] fileArr = files.split(",");
      for (String tmpFile: fileArr) {
        Path tmp = new Path(tmpFile);
        Path newPath = copyRemoteFiles(fs,filesDir, tmp, job, replication);
        try {
          URI pathURI = new URI(newPath.toUri().toString() + "#" + newPath.getName());
          DistributedCache.addCacheFile(pathURI, job);
        } catch(URISyntaxException ue) {
          //should not throw a uri exception 
          throw new IOException("Failed to create uri for " + tmpFile);
        }
        DistributedCache.createSymlink(job);
      }
    }
    
    if (libjars != null) {
      FileSystem.mkdirs(fs, libjarsDir, mapredSysPerms);
      String[] libjarsArr = libjars.split(",");
      for (String tmpjars: libjarsArr) {
        Path tmp = new Path(tmpjars);
        Path newPath = copyRemoteFiles(fs, libjarsDir, tmp, job, replication);
        DistributedCache.addArchiveToClassPath(newPath, job);
      }
    }
    
    
    if (archives != null) {
     FileSystem.mkdirs(fs, archivesDir, mapredSysPerms); 
     String[] archivesArr = archives.split(",");
     for (String tmpArchives: archivesArr) {
       Path tmp = new Path(tmpArchives);
       Path newPath = copyRemoteFiles(fs, archivesDir, tmp, job, replication);
       try {
         URI pathURI = new URI(newPath.toUri().toString() + "#" + newPath.getName());
         DistributedCache.addCacheArchive(pathURI, job);
       } catch(URISyntaxException ue) {
         //should not throw an uri excpetion
         throw new IOException("Failed to create uri for " + tmpArchives);
       }
       DistributedCache.createSymlink(job);
     }
    }
    
    //  set the timestamps of the archives and files
    URI[] tarchives = DistributedCache.getCacheArchives(job);
    if (tarchives != null) {
      StringBuffer archiveTimestamps = 
        new StringBuffer(String.valueOf(DistributedCache.getTimestamp(job, tarchives[0])));
      for (int i = 1; i < tarchives.length; i++) {
        archiveTimestamps.append(",");
        archiveTimestamps.append(String.valueOf(DistributedCache.getTimestamp(job, tarchives[i])));
      }
      DistributedCache.setArchiveTimestamps(job, archiveTimestamps.toString());
    }

    URI[] tfiles = DistributedCache.getCacheFiles(job);
    if (tfiles != null) {
      StringBuffer fileTimestamps = 
        new StringBuffer(String.valueOf(DistributedCache.getTimestamp(job, tfiles[0])));
      for (int i = 1; i < tfiles.length; i++) {
        fileTimestamps.append(",");
        fileTimestamps.append(String.valueOf(DistributedCache.getTimestamp(job, tfiles[i])));
      }
      DistributedCache.setFileTimestamps(job, fileTimestamps.toString());
    }
       
    String originalJarPath = job.getJar();

    if (originalJarPath != null) {           // copy jar to JobTracker's fs
      // use jar name if job is not named. 
      if ("".equals(job.getJobName())){
        job.setJobName(new Path(originalJarPath).getName());
      }
      job.setJar(submitJarFile.toString());
      fs.copyFromLocalFile(new Path(originalJarPath), submitJarFile);
      fs.setReplication(submitJarFile, replication);
      fs.setPermission(submitJarFile, new FsPermission(JOB_FILE_PERMISSION));
    } else {
      LOG.warn("No job jar file set.  User classes may not be found. "+
               "See JobConf(Class) or JobConf#setJar(String).");
    }

    // Set the user's name and working directory
    job.setUser(ugi.getUserName());
    if (ugi.getGroupNames().length > 0) {
      job.set("group.name", ugi.getGroupNames()[0]);
    }
    if (job.getWorkingDirectory() == null) {
      job.setWorkingDirectory(fs.getWorkingDirectory());          
    }

  }

  private UnixUserGroupInformation getUGI(Configuration job) throws IOException {
    UnixUserGroupInformation ugi = null;
    try {
      ugi = UnixUserGroupInformation.login(job, true);
    } catch (LoginException e) {
      throw (IOException)(new IOException(
          "Failed to get the current user's information.").initCause(e));
    }
    return ugi;
  }
  
  /**
   * Submit a job to the MR system.
   * 
   * This returns a handle to the {@link RunningJob} which can be used to track
   * the running-job.
   * 
   * @param jobFile the job configuration.
   * @return a handle to the {@link RunningJob} which can be used to track the
   *         running-job.
   * @throws FileNotFoundException
   * @throws InvalidJobConfException
   * @throws IOException
   */
  public RunningJob submitJob(String jobFile) throws FileNotFoundException, 
                                                     InvalidJobConfException, 
                                                     IOException {
    // Load in the submitted job details
    JobConf job = new JobConf(jobFile);
    return submitJob(job);
  }
    
  // job files are world-wide readable and owner writable
  final public static FsPermission JOB_FILE_PERMISSION = 
    FsPermission.createImmutable((short) 0644); // rw-r--r--

  // job submission directory is world readable/writable/executable
  final static FsPermission JOB_DIR_PERMISSION =
    FsPermission.createImmutable((short) 0777); // rwx-rwx-rwx

  /** Normalize the output path defined in job configuration.
   * Store the normalized path back into job configuration.
   * 
   * @param job job configuration
   * @throws IOException if any error occurs during normalization
   */
  private static void normalizeOutputPath(JobConf job) throws IOException {
    Path out = FileOutputFormat.getOutputPath(job);
    if (out!=null) {
      FileOutputFormat.setOutputPath(job, 
          out.getFileSystem(job).makeQualified(out));
    }
  }
  
  public List<RunningJob> submitJobs(List<JobConf> jobs) 
  	throws FileNotFoundException, InvalidJobConfException, IOException {
	  List<JobID>     jobids = new ArrayList<JobID>();
	  List<RunningJob> rjobs = new ArrayList<RunningJob>();
	  for (JobConf job : jobs) {
		  jobids.add(jobSubmitClient.getNewJobId());
	  }
	  
	  for (int i = 0; i < jobs.size(); i++) {
		  JobConf job = jobs.get(i);
		  if (i > 0) {
			job.set("mapred.job.pipeline", jobids.get(i - 1).toString());
			Class keyClass = jobs.get(i - 1).getOutputKeyClass();
			Class valClass = jobs.get(i - 1).getOutputValueClass();
			job.setInputKeyClass(keyClass);
			job.setInputValueClass(valClass);
		  }
		  
		  if (i < jobs.size() - 1) {
			job.set("mapred.job.dependent", jobids.get(i + 1).toString());
			job.setBoolean("mapred.reduce.pipeline", true);
		  }
		  rjobs.add(submitJob(job, jobids.get(i)));
	  }
	  
	  return rjobs;
  }
  
  
  /**
   * Submit a job to the MR system.
   * This returns a handle to the {@link RunningJob} which can be used to track
   * the running-job.
   * 
   * @param job the job configuration.
   * @return a handle to the {@link RunningJob} which can be used to track the
   *         running-job.
   * @throws FileNotFoundException
   * @throws InvalidJobConfException
   * @throws IOException
   */
  public RunningJob submitJob(JobConf job) throws FileNotFoundException, 
                                  InvalidJobConfException, IOException {
    JobID jobId = jobSubmitClient.getNewJobId();
    return submitJob(job, jobId);
  }
  
  private RunningJob submitJob(JobConf job, JobID jobId) throws FileNotFoundException, 
                                  InvalidJobConfException, IOException {
  
    /*
     * configure the command line options correctly on the submitting dfs
     */
    
    Path submitJobDir = new Path(getSystemDir(), jobId.toString());
    Path submitJarFile = new Path(submitJobDir, "job.jar");
    Path submitSplitFile = new Path(submitJobDir, "job.split");
    configureCommandLineOptions(job, submitJobDir, submitJarFile);
    Path submitJobFile = new Path(submitJobDir, "job.xml");
    
    // Normalize and check the output specification
    normalizeOutputPath(job);
    job.getOutputFormat().checkOutputSpecs(fs, job);

    boolean stream = job.getBoolean("mapred.stream", false) ||
    				 job.getBoolean("mapred.job.monitor", false);
    
    if (!stream) {
    	// Create the splits for the job
    	LOG.debug("Creating splits at " + fs.makeQualified(submitSplitFile));
    	InputSplit[] splits = 
    		job.getInputFormat().getSplits(job, job.getNumMapTasks());
    	// sort the splits into order based on size, so that the biggest
    	// go first
    	Arrays.sort(splits, new Comparator<InputSplit>() {
    		public int compare(InputSplit a, InputSplit b) {
    			try {
    				long left = a.getLength();
    				long right = b.getLength();
    				if (left == right) {
    					return 0;
    				} else if (left < right) {
    					return 1;
    				} else {
    					return -1;
    				}
    			} catch (IOException ie) {
    				throw new RuntimeException("Problem getting input split size",
    						ie);
    			}
    		}
    	});
    	// write the splits to a file for the job tracker
    	FSDataOutputStream out = FileSystem.create(fs,
    			submitSplitFile, new FsPermission(JOB_FILE_PERMISSION));
    	try {
    		writeSplitsFile(splits, out);
    	} finally {
    		out.close();
    	}
    	job.set("mapred.job.split.file", submitSplitFile.toString());
    	job.setNumMapTasks(splits.length);
    }
        
    // Write job file to JobTracker's fs        
    FSDataOutputStream out = FileSystem.create(fs, submitJobFile,
        new FsPermission(JOB_FILE_PERMISSION));

    try {
      job.writeXml(out);
    } finally {
      out.close();
    }

    //
    // Now, actually submit the job (using the submit name)
    //
    JobStatus status = jobSubmitClient.submitJob(jobId);
    if (status != null) {
      return new NetworkedJob(status);
    } else {
      throw new IOException("Could not launch job");
    }
  }

  /** 
   * Checks if the job directory is clean and has all the required components 
   * for (re) starting the job
   */
  public static boolean isJobDirValid(Path jobDirPath, FileSystem fs) 
  throws IOException {
    FileStatus[] contents = fs.listStatus(jobDirPath);
    int matchCount = 0;
    if (contents != null && contents.length >=3) {
      for (FileStatus status : contents) {
        if ("job.xml".equals(status.getPath().getName())) {
          ++matchCount;
        }
        if ("job.jar".equals(status.getPath().getName())) {
          ++matchCount;
        }
        if ("job.split".equals(status.getPath().getName())) {
          ++matchCount;
        }
      }
      if (matchCount == 3) {
        return true;
      }
    }
    return false;
  }

  static class RawSplit implements Writable {
    private String splitClass = "";
    private BytesWritable bytes = new BytesWritable();
    private String[] locations = new String[0];
    long dataLength = 0;

    public void setBytes(byte[] data, int offset, int length) {
      bytes.set(data, offset, length);
    }

    public void setClassName(String className) {
      splitClass = className;
    }
      
    public String getClassName() {
      return splitClass;
    }
      
    public BytesWritable getBytes() {
      return bytes;
    }

    public void clearBytes() {
      bytes = null;
    }
      
    public void setLocations(String[] locations) {
      this.locations = locations;
    }
      
    public String[] getLocations() {
      return locations;
    }
      
    public void readFields(DataInput in) throws IOException {
      splitClass = Text.readString(in);
      dataLength = in.readLong();
      bytes.readFields(in);
      int len = WritableUtils.readVInt(in);
      locations = new String[len];
      for(int i=0; i < len; ++i) {
        locations[i] = Text.readString(in);
      }
    }
      
    public void write(DataOutput out) throws IOException {
      Text.writeString(out, splitClass);
      out.writeLong(dataLength);
      bytes.write(out);
      WritableUtils.writeVInt(out, locations.length);
      for(int i = 0; i < locations.length; i++) {
        Text.writeString(out, locations[i]);
      }        
    }

    public long getDataLength() {
      return dataLength;
    }
    public void setDataLength(long l) {
      dataLength = l;
    }
    
  }
    
  private static final int CURRENT_SPLIT_FILE_VERSION = 0;
  private static final byte[] SPLIT_FILE_HEADER = "SPL".getBytes();
    
  /** Create the list of input splits and write them out in a file for
   *the JobTracker. The format is:
   * <format version>
   * <numSplits>
   * for each split:
   *    <RawSplit>
   * @param splits the input splits to write out
   * @param out the stream to write to
   */
  private void writeSplitsFile(InputSplit[] splits, FSDataOutputStream out) throws IOException {
    out.write(SPLIT_FILE_HEADER);
    WritableUtils.writeVInt(out, CURRENT_SPLIT_FILE_VERSION);
    WritableUtils.writeVInt(out, splits.length);
    DataOutputBuffer buffer = new DataOutputBuffer();
    RawSplit rawSplit = new RawSplit();
    for(InputSplit split: splits) {
      rawSplit.setClassName(split.getClass().getName());
      buffer.reset();
      split.write(buffer);
      rawSplit.setDataLength(split.getLength());
      rawSplit.setBytes(buffer.getData(), 0, buffer.getLength());
      rawSplit.setLocations(split.getLocations());
      rawSplit.write(out);
    }
  }

  /**
   * Read a splits file into a list of raw splits
   * @param in the stream to read from
   * @return the complete list of splits
   * @throws IOException
   */
  static RawSplit[] readSplitFile(DataInput in) throws IOException {
    byte[] header = new byte[SPLIT_FILE_HEADER.length];
    in.readFully(header);
    if (!Arrays.equals(SPLIT_FILE_HEADER, header)) {
      throw new IOException("Invalid header on split file");
    }
    int vers = WritableUtils.readVInt(in);
    if (vers != CURRENT_SPLIT_FILE_VERSION) {
      throw new IOException("Unsupported split version " + vers);
    }
    int len = WritableUtils.readVInt(in);
    RawSplit[] result = new RawSplit[len];
    for(int i=0; i < len; ++i) {
      result[i] = new RawSplit();
      result[i].readFields(in);
    }
    return result;
  }
    
  /**
   * Get an {@link RunningJob} object to track an ongoing job.  Returns
   * null if the id does not correspond to any known job.
   * 
   * @param jobid the jobid of the job.
   * @return the {@link RunningJob} handle to track the job, null if the 
   *         <code>jobid</code> doesn't correspond to any known job.
   * @throws IOException
   */
  public RunningJob getJob(JobID jobid) throws IOException {
    JobStatus status = jobSubmitClient.getJobStatus(jobid);
    if (status != null) {
      return new NetworkedJob(status);
    } else {
      return null;
    }
  }

  /**@deprecated Applications should rather use {@link #getJob(JobID)}. 
   */
  @Deprecated
  public RunningJob getJob(String jobid) throws IOException {
    return getJob(JobID.forName(jobid));
  }
  
  /**
   * Get the information of the current state of the map tasks of a job.
   * 
   * @param jobId the job to query.
   * @return the list of all of the map tips.
   * @throws IOException
   */
  public TaskReport[] getMapTaskReports(JobID jobId) throws IOException {
    return jobSubmitClient.getMapTaskReports(jobId);
  }
  
  /**@deprecated Applications should rather use {@link #getMapTaskReports(JobID)}*/
  @Deprecated
  public TaskReport[] getMapTaskReports(String jobId) throws IOException {
    return getMapTaskReports(JobID.forName(jobId));
  }
  
  /**
   * Get the information of the current state of the reduce tasks of a job.
   * 
   * @param jobId the job to query.
   * @return the list of all of the reduce tips.
   * @throws IOException
   */    
  public TaskReport[] getReduceTaskReports(JobID jobId) throws IOException {
    return jobSubmitClient.getReduceTaskReports(jobId);
  }

  /**
   * Get the information of the current state of the cleanup tasks of a job.
   * 
   * @param jobId the job to query.
   * @return the list of all of the cleanup tips.
   * @throws IOException
   */    
  public TaskReport[] getCleanupTaskReports(JobID jobId) throws IOException {
    return jobSubmitClient.getCleanupTaskReports(jobId);
  }

  /**
   * Get the information of the current state of the setup tasks of a job.
   * 
   * @param jobId the job to query.
   * @return the list of all of the setup tips.
   * @throws IOException
   */    
  public TaskReport[] getSetupTaskReports(JobID jobId) throws IOException {
    return jobSubmitClient.getSetupTaskReports(jobId);
  }

  /**@deprecated Applications should rather use {@link #getReduceTaskReports(JobID)}*/
  @Deprecated
  public TaskReport[] getReduceTaskReports(String jobId) throws IOException {
    return getReduceTaskReports(JobID.forName(jobId));
  }
  
  /**
   * Get status information about the Map-Reduce cluster.
   *  
   * @return the status information about the Map-Reduce cluster as an object
   *         of {@link ClusterStatus}.
   * @throws IOException
   */
  public ClusterStatus getClusterStatus() throws IOException {
    return jobSubmitClient.getClusterStatus();
  }
    

  /** 
   * Get the jobs that are not completed and not failed.
   * 
   * @return array of {@link JobStatus} for the running/to-be-run jobs.
   * @throws IOException
   */
  public JobStatus[] jobsToComplete() throws IOException {
    return jobSubmitClient.jobsToComplete();
  }

  private static void downloadProfile(TaskCompletionEvent e
                                      ) throws IOException  {
    URLConnection connection = 
      new URL(getTaskLogURL(e.getTaskAttemptId(), e.getTaskTrackerHttp()) + 
              "&filter=profile").openConnection();
    InputStream in = connection.getInputStream();
    OutputStream out = new FileOutputStream(e.getTaskAttemptId() + ".profile");
    IOUtils.copyBytes(in, out, 64 * 1024, true);
  }

  /** 
   * Get the jobs that are submitted.
   * 
   * @return array of {@link JobStatus} for the submitted jobs.
   * @throws IOException
   */
  public JobStatus[] getAllJobs() throws IOException {
    return jobSubmitClient.getAllJobs();
  }
  
  /** 
   * Utility that submits a job, then polls for progress until the job is
   * complete.
   * 
   * @param job the job configuration.
   * @throws IOException
   */
  public static RunningJob runJob(JobConf job) throws IOException {
    JobClient jc = new JobClient(job);
    boolean error = true;
    RunningJob running = null;
    String lastReport = null;
    final int MAX_RETRIES = 5;
    int retries = MAX_RETRIES;
    TaskStatusFilter filter;
    try {
      filter = getTaskOutputFilter(job);
    } catch(IllegalArgumentException e) {
      LOG.warn("Invalid Output filter : " + e.getMessage() + 
               " Valid values are : NONE, FAILED, SUCCEEDED, ALL");
      throw e;
    }
    try {
      long startTime = System.currentTimeMillis();
      running = jc.submitJob(job);
      JobID jobId = running.getID();
      LOG.info("Running job: " + jobId);

      int eventCounter = 0;
      boolean profiling = job.getProfileEnabled();
      Configuration.IntegerRanges mapRanges = job.getProfileTaskRange(true);
      Configuration.IntegerRanges reduceRanges = job.getProfileTaskRange(false);
        
      while (true) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {}
        try {
          if (running.isComplete()) {
            break;
          }
          running = jc.getJob(jobId);
          if (running == null) {
            throw new IOException("Unable to fetch job status from server.");
          }
          String report = 
            (" map " + StringUtils.formatPercent(running.mapProgress(), 0)+
             " reduce " + 
             StringUtils.formatPercent(running.reduceProgress(), 0));
          if (!report.equals(lastReport)) {
            LOG.info(report);
            lastReport = report;
          }
            
          TaskCompletionEvent[] events = 
            running.getTaskCompletionEvents(eventCounter); 
          eventCounter += events.length;
          for(TaskCompletionEvent event : events){
            TaskCompletionEvent.Status status = event.getTaskStatus();
            if (profiling && 
                (status == TaskCompletionEvent.Status.SUCCEEDED ||
                 status == TaskCompletionEvent.Status.FAILED) &&
                (event.isMap ? mapRanges : reduceRanges).
                   isIncluded(event.idWithinJob())) {
              downloadProfile(event);
            }
            switch(filter){
            case NONE:
              break;
            case SUCCEEDED:
              if (event.getTaskStatus() == 
                TaskCompletionEvent.Status.SUCCEEDED){
                LOG.info(event.toString());
                displayTaskLogs(event.getTaskAttemptId(), event.getTaskTrackerHttp());
              }
              break; 
            case FAILED:
              if (event.getTaskStatus() == 
                TaskCompletionEvent.Status.FAILED){
                LOG.info(event.toString());
                // Displaying the task diagnostic information
                TaskAttemptID taskId = event.getTaskAttemptId();
                String[] taskDiagnostics = 
                  jc.jobSubmitClient.getTaskDiagnostics(taskId); 
                if (taskDiagnostics != null) {
                  for(String diagnostics : taskDiagnostics){
                    System.err.println(diagnostics);
                  }
                }
                // Displaying the task logs
                displayTaskLogs(event.getTaskAttemptId(), event.getTaskTrackerHttp());
              }
              break; 
            case KILLED:
              if (event.getTaskStatus() == TaskCompletionEvent.Status.KILLED){
                LOG.info(event.toString());
              }
              break; 
            case ALL:
              LOG.info(event.toString());
              displayTaskLogs(event.getTaskAttemptId(), event.getTaskTrackerHttp());
              break;
            }
          }
          retries = MAX_RETRIES;
        } catch (IOException ie) {
          if (--retries == 0) {
            LOG.warn("Final attempt failed, killing job.");
            throw ie;
          }
          LOG.info("Communication problem with server: " +
                   StringUtils.stringifyException(ie));
        }
      }
      if (!running.isSuccessful()) {
        throw new IOException("Job failed!");
      }
      LOG.info("Job complete: " + jobId);
      LOG.info("Elapsed time: " + (System.currentTimeMillis() - startTime) + " msec.");
      running.getCounters().log(LOG);
      error = false;
    } finally {
      if (error && (running != null)) {
        running.killJob();
      }
      jc.close();
    }
    return running;
  }
  
  public void report(RunningJob running, JobConf job) throws IOException {
	  JobID jobId = JobID.forName(running.getJobID());
	    boolean error = true;
	    String lastReport = null;
	    final int MAX_RETRIES = 5;
	    int retries = MAX_RETRIES;
	    TaskStatusFilter filter;
	    try {
	      filter = getTaskOutputFilter(job);
	    } catch(IllegalArgumentException e) {
	      LOG.warn("Invalid Output filter : " + e.getMessage() + 
	               " Valid values are : NONE, FAILED, SUCCEEDED, ALL");
	      throw e;
	    }
	    
	  try {
		  LOG.info("Running job: " + jobId);
		  int eventCounter = 0;
		  boolean profiling = job.getProfileEnabled();
		  Configuration.IntegerRanges mapRanges = job.getProfileTaskRange(true);
		  Configuration.IntegerRanges reduceRanges = job.getProfileTaskRange(false);

		  while (true) {
			  try {
				  Thread.sleep(1000);
			  } catch (InterruptedException e) {}
			  try {
				  if (running.isComplete()) {
					  break;
				  }
				  String report = 
					  (" map " + StringUtils.formatPercent(running.mapProgress(), 0)+
							  " reduce " + 
							  StringUtils.formatPercent(running.reduceProgress(), 0));
				  if (!report.equals(lastReport)) {
					  LOG.info(report);
					  lastReport = report;
				  }

				  TaskCompletionEvent[] events = 
					  running.getTaskCompletionEvents(eventCounter); 
				  eventCounter += events.length;
				  for(TaskCompletionEvent event : events){
					  TaskCompletionEvent.Status status = event.getTaskStatus();
					  if (profiling && 
							  (status == TaskCompletionEvent.Status.SUCCEEDED ||
									  status == TaskCompletionEvent.Status.FAILED) &&
									  (event.isMap ? mapRanges : reduceRanges).
									  isIncluded(event.idWithinJob())) {
						  downloadProfile(event);
					  }
					  switch(filter){
					  case NONE:
						  break;
					  case SUCCEEDED:
						  if (event.getTaskStatus() == 
							  TaskCompletionEvent.Status.SUCCEEDED){
							  LOG.info(event.toString());
							  displayTaskLogs(event.getTaskAttemptId(), event.getTaskTrackerHttp());
						  }
						  break; 
					  case FAILED:
						  if (event.getTaskStatus() == 
							  TaskCompletionEvent.Status.FAILED){
							  LOG.info(event.toString());
							  // Displaying the task diagnostic information
							  TaskAttemptID taskId = event.getTaskAttemptId();
							  String[] taskDiagnostics = 
								  jobSubmitClient.getTaskDiagnostics(taskId); 
							  if (taskDiagnostics != null) {
								  for(String diagnostics : taskDiagnostics){
									  System.err.println(diagnostics);
								  }
							  }
							  // Displaying the task logs
							  displayTaskLogs(event.getTaskAttemptId(), event.getTaskTrackerHttp());
						  }
						  break; 
					  case KILLED:
						  if (event.getTaskStatus() == TaskCompletionEvent.Status.KILLED){
							  LOG.info(event.toString());
						  }
						  break; 
					  case ALL:
						  LOG.info(event.toString());
						  displayTaskLogs(event.getTaskAttemptId(), event.getTaskTrackerHttp());
						  break;
					  }
				  }
				  retries = MAX_RETRIES;
			  } catch (IOException ie) {
				  if (--retries == 0) {
					  LOG.warn("Final attempt failed, killing job.");
				  }
				  LOG.info("Communication problem with server: " +
						  StringUtils.stringifyException(ie));
			  }
		  }
		  if (!running.isSuccessful()) {
			  throw new IOException("Job failed!");
		  }
		  LOG.info("Job complete: " + jobId);
		  running.getCounters().log(LOG);
		  error = false;
	  } finally {
		  if (error && (running != null)) {
			  running.killJob();
		  }
	  }
  }

  static String getTaskLogURL(TaskAttemptID taskId, String baseUrl) {
    return (baseUrl + "/tasklog?plaintext=true&taskid=" + taskId); 
  }
  
  private static void displayTaskLogs(TaskAttemptID taskId, String baseUrl)
    throws IOException {
    // The tasktracker for a 'failed/killed' job might not be around...
    if (baseUrl != null) {
      // Construct the url for the tasklogs
      String taskLogUrl = getTaskLogURL(taskId, baseUrl);
      
      // Copy tasks's stdout of the JobClient
      getTaskLogs(taskId, new URL(taskLogUrl+"&filter=stdout"), System.out);
        
      // Copy task's stderr to stderr of the JobClient 
      getTaskLogs(taskId, new URL(taskLogUrl+"&filter=stderr"), System.err);
    }
  }
    
  private static void getTaskLogs(TaskAttemptID taskId, URL taskLogUrl, 
                                  OutputStream out) {
    try {
      URLConnection connection = taskLogUrl.openConnection();
      BufferedReader input = 
        new BufferedReader(new InputStreamReader(connection.getInputStream()));
      BufferedWriter output = 
        new BufferedWriter(new OutputStreamWriter(out));
      try {
        String logData = null;
        while ((logData = input.readLine()) != null) {
          if (logData.length() > 0) {
            output.write(taskId + ": " + logData + "\n");
            output.flush();
          }
        }
      } finally {
        input.close();
      }
    }catch(IOException ioe){
      LOG.warn("Error reading task output" + ioe.getMessage()); 
    }
  }    

  static Configuration getConfiguration(String jobTrackerSpec)
  {
    Configuration conf = new Configuration();
    if (jobTrackerSpec != null) {        
      if (jobTrackerSpec.indexOf(":") >= 0) {
        conf.set("mapred.job.tracker", jobTrackerSpec);
      } else {
        String classpathFile = "hadoop-" + jobTrackerSpec + ".xml";
        URL validate = conf.getResource(classpathFile);
        if (validate == null) {
          throw new RuntimeException(classpathFile + " not found on CLASSPATH");
        }
        conf.addResource(classpathFile);
      }
    }
    return conf;
  }

  /**
   * Sets the output filter for tasks. only those tasks are printed whose
   * output matches the filter. 
   * @param newValue task filter.
   */
  @Deprecated
  public void setTaskOutputFilter(TaskStatusFilter newValue){
    this.taskOutputFilter = newValue;
  }
    
  /**
   * Get the task output filter out of the JobConf.
   * 
   * @param job the JobConf to examine.
   * @return the filter level.
   */
  public static TaskStatusFilter getTaskOutputFilter(JobConf job) {
    return TaskStatusFilter.valueOf(job.get("jobclient.output.filter", 
                                            "FAILED"));
  }
    
  /**
   * Modify the JobConf to set the task output filter.
   * 
   * @param job the JobConf to modify.
   * @param newValue the value to set.
   */
  public static void setTaskOutputFilter(JobConf job, 
                                         TaskStatusFilter newValue) {
    job.set("jobclient.output.filter", newValue.toString());
  }
    
  /**
   * Returns task output filter.
   * @return task filter. 
   */
  @Deprecated
  public TaskStatusFilter getTaskOutputFilter(){
    return this.taskOutputFilter; 
  }

  private String getJobPriorityNames() {
    StringBuffer sb = new StringBuffer();
    for (JobPriority p : JobPriority.values()) {
      sb.append(p.name()).append(" ");
    }
    return sb.substring(0, sb.length()-1);
  }
  
  /**
   * Display usage of the command-line tool and terminate execution
   */
  private void displayUsage(String cmd) {
    String prefix = "Usage: JobClient ";
    String jobPriorityValues = getJobPriorityNames();
    if("-submit".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-file>]");
    } else if ("-status".equals(cmd) || "-kill".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id>]");
    } else if ("-counter".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id> <group-name> <counter-name>]");
    } else if ("-events".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id> <from-event-#> <#-of-events>]");
    } else if ("-history".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <jobOutputDir>]");
    } else if ("-list".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " [all]]");
    } else if ("-kill-task".equals(cmd) || "-fail-task".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <task-id>]");
    } else if ("-set-priority".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id> <priority>]. " +
          "Valid values for priorities are: " 
          + jobPriorityValues); 
    } else {
      System.err.printf(prefix + "<command> <args>\n");
      System.err.printf("\t[-submit <job-file>]\n");
      System.err.printf("\t[-status <job-id>]\n");
      System.err.printf("\t[-counter <job-id> <group-name> <counter-name>]\n");
      System.err.printf("\t[-kill <job-id>]\n");
      System.err.printf("\t[-set-priority <job-id> <priority>]. " +
                                      "Valid values for priorities are: " +
                                      jobPriorityValues + "\n");
      System.err.printf("\t[-events <job-id> <from-event-#> <#-of-events>]\n");
      System.err.printf("\t[-history <jobOutputDir>]\n");
      System.err.printf("\t[-list [all]]\n");
      System.err.printf("\t[-kill-task <task-id>]\n");
      System.err.printf("\t[-fail-task <task-id>]\n\n");
      ToolRunner.printGenericCommandUsage(System.out);
    }
  }
    
  public int run(String[] argv) throws Exception {
    int exitCode = -1;
    if (argv.length < 1) {
      displayUsage("");
      return exitCode;
    }    
    // process arguments
    String cmd = argv[0];
    String submitJobFile = null;
    String jobid = null;
    String taskid = null;
    String outputDir = null;
    String counterGroupName = null;
    String counterName = null;
    String newPriority = null;
    int fromEvent = 0;
    int nEvents = 0;
    boolean getStatus = false;
    boolean getCounter = false;
    boolean killJob = false;
    boolean listEvents = false;
    boolean viewHistory = false;
    boolean viewAllHistory = false;
    boolean listJobs = false;
    boolean listAllJobs = false;
    boolean killTask = false;
    boolean failTask = false;
    boolean setJobPriority = false;

    if ("-submit".equals(cmd)) {
      if (argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      submitJobFile = argv[1];
    } else if ("-status".equals(cmd)) {
      if (argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      jobid = argv[1];
      getStatus = true;
    } else if("-counter".equals(cmd)) {
      if (argv.length != 4) {
        displayUsage(cmd);
        return exitCode;
      }
      getCounter = true;
      jobid = argv[1];
      counterGroupName = argv[2];
      counterName = argv[3];
    } else if ("-kill".equals(cmd)) {
      if (argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      jobid = argv[1];
      killJob = true;
    } else if ("-set-priority".equals(cmd)) {
      if (argv.length != 3) {
        displayUsage(cmd);
        return exitCode;
      }
      jobid = argv[1];
      newPriority = argv[2];
      try {
        JobPriority jp = JobPriority.valueOf(newPriority); 
      } catch (IllegalArgumentException iae) {
        displayUsage(cmd);
        return exitCode;
      }
      setJobPriority = true; 
    } else if ("-events".equals(cmd)) {
      if (argv.length != 4) {
        displayUsage(cmd);
        return exitCode;
      }
      jobid = argv[1];
      fromEvent = Integer.parseInt(argv[2]);
      nEvents = Integer.parseInt(argv[3]);
      listEvents = true;
    } else if ("-history".equals(cmd)) {
      if (argv.length != 2 && !(argv.length == 3 && "all".equals(argv[1]))) {
         displayUsage(cmd);
         return exitCode;
      }
      viewHistory = true;
      if (argv.length == 3 && "all".equals(argv[1])) {
         viewAllHistory = true;
         outputDir = argv[2];
      } else {
         outputDir = argv[1];
      }
    } else if ("-list".equals(cmd)) {
      if (argv.length != 1 && !(argv.length == 2 && "all".equals(argv[1]))) {
        displayUsage(cmd);
        return exitCode;
      }
      if (argv.length == 2 && "all".equals(argv[1])) {
        listAllJobs = true;
      } else {
        listJobs = true;
      }
    } else if("-kill-task".equals(cmd)) {
      if(argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      killTask = true;
      taskid = argv[1];
    } else if("-fail-task".equals(cmd)) {
      if(argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      failTask = true;
      taskid = argv[1];
    } else {
      displayUsage(cmd);
      return exitCode;
    }

    // initialize JobClient
    JobConf conf = null;
    if (submitJobFile != null) {
      conf = new JobConf(submitJobFile);
    } else {
      conf = new JobConf(getConf());
    }
    init(conf);
        
    // Submit the request
    try {
      if (submitJobFile != null) {
        RunningJob job = submitJob(conf);
        System.out.println("Created job " + job.getID());
        exitCode = 0;
      } else if (getStatus) {
        RunningJob job = getJob(JobID.forName(jobid));
        if (job == null) {
          System.out.println("Could not find job " + jobid);
        } else {
          System.out.println();
          System.out.println(job);
          System.out.println(job.getCounters());
          exitCode = 0;
        }
      } else if (getCounter) {
        RunningJob job = getJob(JobID.forName(jobid));
        if (job == null) {
          System.out.println("Could not find job " + jobid);
        } else {
          Counters counters = job.getCounters();
          Group group = counters.getGroup(counterGroupName);
          Counter counter = group.getCounterForName(counterName);
          System.out.println(counter.getCounter());
          exitCode = 0;
        }
      } else if (killJob) {
        RunningJob job = getJob(JobID.forName(jobid));
        if (job == null) {
          System.out.println("Could not find job " + jobid);
        } else {
          job.killJob();
          System.out.println("Killed job " + jobid);
          exitCode = 0;
        }
      } else if (setJobPriority) {
        RunningJob job = getJob(JobID.forName(jobid));
        if (job == null) {
          System.out.println("Could not find job " + jobid);
        } else {
          job.setJobPriority(newPriority);
          System.out.println("Changed job priority.");
          exitCode = 0;
        } 
      } else if (viewHistory) {
        viewHistory(outputDir, viewAllHistory);
        exitCode = 0;
      } else if (listEvents) {
        listEvents(JobID.forName(jobid), fromEvent, nEvents);
        exitCode = 0;
      } else if (listJobs) {
        listJobs();
        exitCode = 0;
      } else if (listAllJobs) {
          listAllJobs();
          exitCode = 0;
      } else if(killTask) {
        if(jobSubmitClient.killTask(TaskAttemptID.forName(taskid), false)) {
          System.out.println("Killed task " + taskid);
          exitCode = 0;
        } else {
          System.out.println("Could not kill task " + taskid);
          exitCode = -1;
        }
      } else if(failTask) {
        if(jobSubmitClient.killTask(TaskAttemptID.forName(taskid), true)) {
          System.out.println("Killed task " + taskid + " by failing it");
          exitCode = 0;
        } else {
          System.out.println("Could not fail task " + taskid);
          exitCode = -1;
        }
      }
    } finally {
      close();
    }
    return exitCode;
  }

  private void viewHistory(String outputDir, boolean all) 
    throws IOException {
    HistoryViewer historyViewer = new HistoryViewer(outputDir,
                                        getConf(), all);
    historyViewer.print();
  }
  
  /**
   * List the events for the given job
   * @param jobId the job id for the job's events to list
   * @throws IOException
   */
  private void listEvents(JobID jobId, int fromEventId, int numEvents)
    throws IOException {
    TaskCompletionEvent[] events = 
      jobSubmitClient.getTaskCompletionEvents(jobId, fromEventId, numEvents);
    System.out.println("Task completion events for " + jobId);
    System.out.println("Number of events (from " + fromEventId + 
                       ") are: " + events.length);
    for(TaskCompletionEvent event: events) {
      System.out.println(event.getTaskStatus() + " " + event.getTaskAttemptId() + " " + 
                         getTaskLogURL(event.getTaskAttemptId(), 
                                       event.getTaskTrackerHttp()));
    }
  }

  /**
   * Dump a list of currently running jobs
   * @throws IOException
   */
  private void listJobs() throws IOException {
    JobStatus[] jobs = jobsToComplete();
    if (jobs == null)
      jobs = new JobStatus[0];

    System.out.printf("%d jobs currently running\n", jobs.length);
    displayJobList(jobs);
  }
    
  /**
   * Dump a list of all jobs submitted.
   * @throws IOException
   */
  private void listAllJobs() throws IOException {
    JobStatus[] jobs = getAllJobs();
    if (jobs == null)
      jobs = new JobStatus[0];
    System.out.printf("%d jobs submitted\n", jobs.length);
    System.out.printf("States are:\n\tRunning : 1\tSucceded : 2" +
    "\tFailed : 3\tPrep : 4\n");
    displayJobList(jobs);
  }

  void displayJobList(JobStatus[] jobs) {
    System.out.printf("JobId\tState\tStartTime\tUserName\tPriority\tSchedulingInfo\n");
    for (JobStatus job : jobs) {
      System.out.printf("%s\t%d\t%d\t%s\t%s\t%s\n", job.getJobID(), job.getRunState(),
          job.getStartTime(), job.getUsername(), 
          job.getJobPriority().name(), job.getSchedulingInfo());
    }
  }

  /**
   * Get status information about the max available Maps in the cluster.
   *  
   * @return the max available Maps in the cluster
   * @throws IOException
   */
  public int getDefaultMaps() throws IOException {
    return getClusterStatus().getMaxMapTasks();
  }

  /**
   * Get status information about the max available Reduces in the cluster.
   *  
   * @return the max available Reduces in the cluster
   * @throws IOException
   */
  public int getDefaultReduces() throws IOException {
    return getClusterStatus().getMaxReduceTasks();
  }

  /**
   * Grab the jobtracker system directory path where job-specific files are to be placed.
   * 
   * @return the system directory where job-specific files are to be placed.
   */
  public Path getSystemDir() {
    if (sysDir == null) {
      sysDir = new Path(jobSubmitClient.getSystemDir());
    }
    return sysDir;
  }
  
  
  /**
   * Return an array of queue information objects about all the Job Queues
   * configured.
   * 
   * @return Array of JobQueueInfo objects
   * @throws IOException
   */
  public JobQueueInfo[] getQueues() throws IOException {
    return jobSubmitClient.getQueues();
  }
  
  /**
   * Gets all the jobs which were added to particular Job Queue
   * 
   * @param queueName name of the Job Queue
   * @return Array of jobs present in the job queue
   * @throws IOException
   */
  
  public JobStatus[] getJobsFromQueue(String queueName) throws IOException {
    return jobSubmitClient.getJobsFromQueue(queueName);
  }
  
  /**
   * Gets the queue information associated to a particular Job Queue
   * 
   * @param queueName name of the job queue.
   * @return Queue information associated to particular queue.
   * @throws IOException
   */
  public JobQueueInfo getQueueInfo(String queueName) throws IOException {
    return jobSubmitClient.getQueueInfo(queueName);
  }
  

  /**
   */
  public static void main(String argv[]) throws Exception {
    int res = ToolRunner.run(new JobClient(), argv);
    System.exit(res);
  }
}

