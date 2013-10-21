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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.io.*;
import java.lang.*;
import java.util.*;
import java.net.*;

//add by wei
//import java.util.Math;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.server.jobtracker.TaskTracker;

//add by wei
import org.apache.hadoop.mapred.NodeResource;

/**
 * A {@link TaskScheduler} that keeps jobs in a queue in priority order (FIFO
 * by default).
 */
class JobQueueTaskScheduler extends TaskScheduler {
  
  private static final int MIN_CLUSTER_SIZE_FOR_PADDING = 3;
  public static final Log LOG = LogFactory.getLog(JobQueueTaskScheduler.class);
  
  protected JobQueueJobInProgressListener jobQueueJobInProgressListener;
  protected EagerTaskInitializationListener eagerTaskInitializationListener;
  private float padFraction;
    
  //add by wei
  protected static double DISKTHRESHOLD = 500;
  protected static  Map<String, NodeResource> resources
    = new HashMap<String, NodeResource>();
  protected volatile boolean running = false;
  
  public JobQueueTaskScheduler() {
    this.jobQueueJobInProgressListener = new JobQueueJobInProgressListener();
  }

/*  public void initResources() {
    NodeResource nodeMasterTest = new NodeResource(0, 0);
    resources.put("mastertest", nodeMasterTest);
    NodeResource nodeSlave1Test = new NodeResource(0, 0);
    resources.put("slave1test", nodeSlave1Test);
  }*/


  
  @Override
  public synchronized void start() throws IOException {
    try {
      super.start();
      taskTrackerManager.addJobInProgressListener(jobQueueJobInProgressListener);
      eagerTaskInitializationListener.setTaskTrackerManager(taskTrackerManager);
      eagerTaskInitializationListener.start();
      taskTrackerManager.addJobInProgressListener(
          eagerTaskInitializationListener);
      running = true;
      initResources();
      new UpdateResourceThread().start();
    } catch (Exception e) {
      LOG.error("Failed to start threads ", e);
    }
  }
  
  @Override
  public synchronized void terminate() throws IOException {
    if (jobQueueJobInProgressListener != null) {
      taskTrackerManager.removeJobInProgressListener(
          jobQueueJobInProgressListener);
    }
    if (eagerTaskInitializationListener != null) {
      taskTrackerManager.removeJobInProgressListener(
          eagerTaskInitializationListener);
      eagerTaskInitializationListener.terminate();
    }
    super.terminate();
    running=false;
  }
  
  @Override
  public synchronized void setConf(Configuration conf) {
    super.setConf(conf);
    padFraction = conf.getFloat("mapred.jobtracker.taskalloc.capacitypad", 
                                 0.01f);
    this.eagerTaskInitializationListener =
      new EagerTaskInitializationListener(conf);
  }

  @Override
  public synchronized List<Task> assignTasks(TaskTracker taskTracker)
      throws IOException {
    // Assigned tasks
    List<Task> assignedTasks = new ArrayList<Task>();
   
    TaskTrackerStatus taskTrackerStatus = taskTracker.getStatus(); 
    ClusterStatus clusterStatus = taskTrackerManager.getClusterStatus();
    final int numTaskTrackers = clusterStatus.getTaskTrackers();
    final int clusterMapCapacity = clusterStatus.getMaxMapTasks();
    final int clusterReduceCapacity = clusterStatus.getMaxReduceTasks();

    Collection<JobInProgress> jobQueue =
      jobQueueJobInProgressListener.getJobQueue();

    //
    // Get map + reduce counts for the current tracker.
    //
    final int trackerMapCapacity = taskTrackerStatus.getMaxMapSlots();
    final int trackerReduceCapacity = taskTrackerStatus.getMaxReduceSlots();
    final int trackerRunningMaps = taskTrackerStatus.countMapTasks();
    final int trackerRunningReduces = taskTrackerStatus.countReduceTasks();


    //
    // Compute (running + pending) map and reduce task numbers across pool
    //
    int remainingReduceLoad = 0;
    int remainingMapLoad = 0;
    synchronized (jobQueue) {
      for (JobInProgress job : jobQueue) {
        if (job.getStatus().getRunState() == JobStatus.RUNNING) {
          remainingMapLoad += (job.desiredMaps() - job.finishedMaps());
          if (job.scheduleReduces()) {
            remainingReduceLoad += 
              (job.desiredReduces() - job.finishedReduces());
          }
        }
      }
    }

    // Compute the 'load factor' for maps and reduces
    double mapLoadFactor = 0.0;
    if (clusterMapCapacity > 0) {
      mapLoadFactor = (double)remainingMapLoad / clusterMapCapacity;
    }
    double reduceLoadFactor = 0.0;
    if (clusterReduceCapacity > 0) {
      reduceLoadFactor = (double)remainingReduceLoad / clusterReduceCapacity;
    }
        
    //
    // In the below steps, we allocate first map tasks (if appropriate),
    // and then reduce tasks if appropriate.  We go through all jobs
    // in order of job arrival; jobs only get serviced if their 
    // predecessors are serviced, too.
    //

    //
    // We assign tasks to the current taskTracker if the given machine 
    // has a workload that's less than the maximum load of that kind of
    // task.
    // However, if the cluster is close to getting loaded i.e. we don't
    // have enough _padding_ for speculative executions etc., we only 
    // schedule the "highest priority" task i.e. the task from the job 
    // with the highest priority.
    //
    
    final int trackerCurrentMapCapacity = 
      Math.min((int)Math.ceil(mapLoadFactor * trackerMapCapacity), 
                              trackerMapCapacity);
    int availableMapSlots = trackerCurrentMapCapacity - trackerRunningMaps;
    boolean exceededMapPadding = false;
    if (availableMapSlots > 0) {
      exceededMapPadding = 
        exceededPadding(true, clusterStatus, trackerMapCapacity);
    }
    
    int numLocalMaps = 0;
    int numNonLocalMaps = 0;
    boolean canMeetDeadline = false;
    int j = 0;
    boolean diskBottleneck = false;

    String host = taskTracker.getStatus().getHost();
    NodeResource nodeResource = resources.get(host);
  //  System.out.printf("+++current time:%d, nodeResource address:%s, cpu usage:%f %n", System.currentTimeMillis(), 
 //                     Integer.toHexString(System.identityHashCode(nodeResource)), nodeResource.getCpuUsage());
    System.out.printf("$$$The total map free slots in TaskTracker %s is %d %n", taskTracker.getStatus().getHost(), availableMapSlots);
    scheduleMaps:
    for (int i=0; i < availableMapSlots; ++i) {
      JobInProgress job = null;
      JobInProgress firstJob = null;
      JobInProgress maxProgressJob = null;
      synchronized (jobQueue) {
        for (JobInProgress jobTmp : jobQueue) {
  //      System.out.printf("!!!JobName=%s Deadline=%d %n", jobTmp.getProfile().getJobName(), jobTmp.getJobDeadline());  
/*	if(firstJob == null && jobTmp.getStatus().getRunState() == JobStatus.RUNNING ) {
            firstJob = jobTmp;
            j = i + 1;
          }*/
   
          if (jobTmp.getStatus().getRunState() != JobStatus.RUNNING) {
            continue;
          }
	  canMeetDeadline = canMeetDeadline(jobTmp);
 //         System.out.printf("***JobName=%s, canMeetDeadline=%b, currentTime=%d %n", jobTmp.getProfile().getJobName(), canMeetDeadline, System.currentTimeMillis()); 
          //add by wei
          if (jobTmp.getStatus().getRunState() == JobStatus.RUNNING) {
            if (!canMeetDeadline) {
              job = jobTmp;
     //         j = i + 1;
              break;
            }

            if (diskBottleneck(jobTmp, taskTracker)) {
              diskBottleneck = true;
              continue;
            } 
            else {
              if((maxProgressJob == null) || (predictMapNormalizedTCT(jobTmp, nodeResource) < predictMapNormalizedTCT(maxProgressJob, nodeResource))) {
                maxProgressJob = jobTmp; 
                diskBottleneck = false;
              }
	    }
          }
     /*     if(jobTmp.getStatus().getRunState() == JobStatus.RUNNING && canMeetDeadline)
            continue;
          job = jobTmp;
          j = i + 1;
          break;*/
   //       j = i + 1;
        }
      
        //add by wei
        // Check if we found a job that could not meet deadline
        if (job == null) {
	  if(maxProgressJob != null) {
	    job = maxProgressJob;
	  }
	  else {
	    // any job we pick will cause IO bottleneck
	    break;
	  }
	}
	// if we reach here, we have found a job to run

     //   job = jobQueue.iterator.next();
     //     job = firstJob;

    /*    if(job == null)
          job = maxProgressJob;*/
        
      //  System.out.printf("@@@Job %s gets the %dth map free slot from TaskTracker %s %n", job.getProfile().getJobName(), j, taskTracker.getStatus().getHost());
        System.out.printf("@@@Job %s gets the map free slot from TaskTracker %s %n", job.getProfile().getJobName(), taskTracker.getStatus().getHost());

          Task t = null;
          
          // Try to schedule a node-local or rack-local Map task
          t = 
            job.obtainNewNodeOrRackLocalMapTask(taskTrackerStatus, 
                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts());
          if (t != null) {
            assignedTasks.add(t);
            ++numLocalMaps;
            
            // Don't assign map tasks to the hilt!
            // Leave some free slots in the cluster for future task-failures,
            // speculative tasks etc. beyond the highest priority job
            if (exceededMapPadding) {
              break scheduleMaps;
            }
           
            // Try all jobs again for the next Map task 
            break;
          }
          
          // Try to schedule a node-local or rack-local Map task
          t = 
            job.obtainNewNonLocalMapTask(taskTrackerStatus, numTaskTrackers,
                                   taskTrackerManager.getNumberOfUniqueHosts());
          
          if (t != null) {
            assignedTasks.add(t);
            ++numNonLocalMaps;
            
            // We assign at most 1 off-switch or speculative task
            // This is to prevent TaskTrackers from stealing local-tasks
            // from other TaskTrackers.
            break scheduleMaps;
          }
      }
    }
    int assignedMaps = assignedTasks.size();

    //
    // Same thing, but for reduce tasks
    // However we _never_ assign more than 1 reduce task per heartbeat
    //
    final int trackerCurrentReduceCapacity = 
      Math.min((int)Math.ceil(reduceLoadFactor * trackerReduceCapacity), 
               trackerReduceCapacity);
    final int availableReduceSlots = 
      Math.min((trackerCurrentReduceCapacity - trackerRunningReduces), 1);
    boolean exceededReducePadding = false;
    if (availableReduceSlots > 0) {
      exceededReducePadding = exceededPadding(false, clusterStatus, 
                                              trackerReduceCapacity);
      synchronized (jobQueue) {
        for (JobInProgress job : jobQueue) {
          if (job.getStatus().getRunState() != JobStatus.RUNNING ||
              job.numReduceTasks == 0) {
            continue;
          }

          Task t = 
            job.obtainNewReduceTask(taskTrackerStatus, numTaskTrackers, 
                                    taskTrackerManager.getNumberOfUniqueHosts()
                                    );
          if (t != null) {
            assignedTasks.add(t);
            break;
          }
          
          // Don't assign reduce tasks to the hilt!
          // Leave some free slots in the cluster for future task-failures,
          // speculative tasks etc. beyond the highest priority job
          if (exceededReducePadding) {
            break;
          }
        }
      }
    }
    
    if (LOG.isDebugEnabled()) {
      LOG.debug("Task assignments for " + taskTrackerStatus.getTrackerName() + " --> " +
                "[" + mapLoadFactor + ", " + trackerMapCapacity + ", " + 
                trackerCurrentMapCapacity + ", " + trackerRunningMaps + "] -> [" + 
                (trackerCurrentMapCapacity - trackerRunningMaps) + ", " +
                assignedMaps + " (" + numLocalMaps + ", " + numNonLocalMaps + 
                ")] [" + reduceLoadFactor + ", " + trackerReduceCapacity + ", " + 
                trackerCurrentReduceCapacity + "," + trackerRunningReduces + 
                "] -> [" + (trackerCurrentReduceCapacity - trackerRunningReduces) + 
                ", " + (assignedTasks.size()-assignedMaps) + "]");
    }

    return assignedTasks;
    
  }

  private boolean exceededPadding(boolean isMapTask, 
                                  ClusterStatus clusterStatus, 
                                  int maxTaskTrackerSlots) { 
    int numTaskTrackers = clusterStatus.getTaskTrackers();
    int totalTasks = 
      (isMapTask) ? clusterStatus.getMapTasks() : 
        clusterStatus.getReduceTasks();
    int totalTaskCapacity = 
      isMapTask ? clusterStatus.getMaxMapTasks() : 
                  clusterStatus.getMaxReduceTasks();

    Collection<JobInProgress> jobQueue =
      jobQueueJobInProgressListener.getJobQueue();

    boolean exceededPadding = false;
    synchronized (jobQueue) {
      int totalNeededTasks = 0;
      for (JobInProgress job : jobQueue) {
        if (job.getStatus().getRunState() != JobStatus.RUNNING ||
            job.numReduceTasks == 0) {
          continue;
        }

        //
        // Beyond the highest-priority task, reserve a little 
        // room for failures and speculative executions; don't 
        // schedule tasks to the hilt.
        //
        totalNeededTasks += 
          isMapTask ? job.desiredMaps() : job.desiredReduces();
        int padding = 0;
        if (numTaskTrackers > MIN_CLUSTER_SIZE_FOR_PADDING) {
          padding = 
            Math.min(maxTaskTrackerSlots,
                     (int) (totalNeededTasks * padFraction));
        }
        if (totalTasks + padding >= totalTaskCapacity) {
          exceededPadding = true;
          break;
        }
      }
    }

    return exceededPadding;
  }

  @Override
  public synchronized Collection<JobInProgress> getJobs(String queueName) {
    return jobQueueJobInProgressListener.getJobQueue();
  }  

  public double predictMapTaskExecTime(JobInProgress job) {
    double mapTaskExecTime = 0;
    String jobName = job.getProfile().getJobName();
    if (jobName.equals("PiEstimator")) {
      mapTaskExecTime = 5;
    }  else if (jobName.equals("word count")) {
    	  mapTaskExecTime = 40;
    }  else if (jobName.equals("TeraSort")) {
          mapTaskExecTime = 7.5;
    }
    return mapTaskExecTime;
  } 


  public double dedicatedMapTaskExecTime(JobInProgress job) {
    double dedicatedMapTaskExecTime = 0;
    String jobName = job.getProfile().getJobName();
    if (jobName.equals("PiEstimator")) {
      dedicatedMapTaskExecTime = 4567.62;
    }  else if (jobName.equals("word count")) {
    	  dedicatedMapTaskExecTime = 39239.4;
    }  else if (jobName.equals("TeraSort")) {
          dedicatedMapTaskExecTime = 12278.45;
    }  else if (jobName.equals("sorter")) {
    	  dedicatedMapTaskExecTime = 9665.96;
    }  else if (jobName.equals("grep-search")) {
    	  dedicatedMapTaskExecTime = 11484.4;
    }  else if (jobName.equals("grep-sort")) {
    	  dedicatedMapTaskExecTime = 5175.125;
    }
    return dedicatedMapTaskExecTime;
  }

  public double predictMapNormalizedTCT(JobInProgress job, NodeResource nodeResource) {
    double mapNormalizedTCT = 0;
    double webCpuUsage = nodeResource.getCpuUsage();
    double a = 0;
    double b = 0;
    double c = 0;
    double d = 0;
    String jobName = job.getProfile().getJobName();
    if (jobName.equals("PiEstimator")) {
       a = 1.004;  
       b = 0.001889;  
       c = 0.007551;  
       d = 0.03663;   
    }  else if (jobName.equals("word count")) {
       a = 0.9472;  
       b = 0.0076;  
       c = 0.0001143;  
       d = 0.06541;  
    }  else if (jobName.equals("TeraSort")) {

       a = 1.118;  
       b = 0.004328;  
       c = 0.002235;  
       d = 0.04902;
     } else if (jobName.equals("sorter")) {
       a = 1.235;  
       b = 0.003595;  
       c = 0.07363;  
       d = 0.02306;  

     } else if (jobName.equals("grep-search")) {
       a = 0.9232;  
       b = 0.005957;  
       c = 0.001579;  
       d = 0.04666;  
     } else if (jobName.equals("grep-sort")) {
       a = 0.929;  
       b = 0.002232;  
       c = 0.002356;  
       d = 0.04242;  
     }

    mapNormalizedTCT = a * Math.exp(b * webCpuUsage) + c * Math.exp(d * webCpuUsage);
    return mapNormalizedTCT;
  }

/*  public double jobProgress(JobInProgress job, NodeResource nodeResource){
    double jobProgress = 0;

    NodeResource dedicatedNodeResource =  new NodeResource(0, 0);
    jobProgress = predictMapTaskExecTime(job, dedicatedNodeResource) / (predictMapTaskExecTime(job, nodeResource));

    return jobProgress;

  }*/
                                 
/*  public boolean canMeetDeadline(JobInProgress job){
    boolean canMeetDeadline;
    int pendingMapTasks;
    int currentMapSlots;
    double dedicatedMapTaskExecTime;
    long remainingTime;
    NodeResource dedicatedNodeResource = null;

    dedicatedNodeResource = new NodeResource(0);
    pendingMapTasks = job.pendingMaps();
    currentMapSlots = job.runningMaps();
    remainingTime = (job.getJobDeadline() - System.currentTimeMillis());
    dedicatedMapTaskExecTime = predictMapTaskExecTime(job, dedicatedNodeResource);
    if (currentMapSlots == 0) {
      canMeetDeadline = false;
      return canMeetDeadline;
    }
    canMeetDeadline = (pendingMapTasks * dedicatedMapTaskExecTime * 1000 / currentMapSlots < remainingTime);
    return canMeetDeadline;  
  
 }*/

public double predictJobDiskDemand(JobInProgress job) {
  String jobName = job.getProfile().getJobName();
  double diskDemand = 0.0;
    if (jobName.equals("PiEstimator")) {
      diskDemand = 10.0;
    }  else if (jobName.equals("word count")) {
        diskDemand = 20.0;
    }  else if (jobName.equals("TeraSort")) {
        diskDemand = 15.0;
    }   
    return diskDemand; 

}

public boolean diskBottleneck(JobInProgress job, TaskTracker taskTracker) {
  boolean diskBottleneck = false;
  String taskTrackerHost = taskTracker.getStatus().getHost();
  double taskTrackerCpuUsage = resources.get(taskTrackerHost).getCpuUsage();
  double predictDiskDemand = predictJobDiskDemand(job);
   
  Set<Map.Entry<String, NodeResource>> entries = resources.entrySet();
  for (Map.Entry<String, NodeResource> entry:entries) {
    String host = entry.getKey();
    NodeResource nodeResource = entry.getValue();
    double cpu = nodeResource.getCpuUsage();
    double disk = nodeResource.getDisk();
    boolean isDedicated = false;
    boolean trackerIsShared = false;
    if((int)taskTrackerCpuUsage != 0){
        trackerIsShared = true;
    }
    if(cpu == 0) {
	isDedicated = true;
    }
    if (isDedicated && trackerIsShared && disk + predictDiskDemand > DISKTHRESHOLD) {
      diskBottleneck = true;
    }
  }

  return diskBottleneck;
}

  public boolean canMeetDeadline(JobInProgress job){
    boolean canMeetDeadline = false;
    int pendingMapTasks;
    long remainingTime;
//    int[] taskNums = new int[100];
    int totalTaskNums = 0;
//    int i = 0;

    pendingMapTasks = job.pendingMaps();
    remainingTime = (job.getJobDeadline() - System.currentTimeMillis());

    Set<Map.Entry<String, Integer>> entries = job.getSlotsHashMap().entrySet();
    for (Map.Entry<String, Integer> entry:entries) {
      String host = entry.getKey();
      Integer slotsNum = entry.getValue();
      NodeResource nodeResouce = resources.get(host);
      double predictMapNormalizedTCT = predictMapNormalizedTCT(job, nodeResouce);      
      double predictMapTaskExecTime = dedicatedMapTaskExecTime(job) * predictMapNormalizedTCT; 
//      taskNums[i] = (int)(remainingTime / (predictMapTaskExecTime * 1000)) * slotsNum;
      totalTaskNums += (int)(remainingTime / (predictMapTaskExecTime * 1000)) * slotsNum;
//      System.out.printf("AAAjobName=%s, host=%s, slotsNum=%d, TaskExecTime=%f, pendingTasks=%d, TaskNums=%d %n", job.getProfile().getJobName(), 
//                        host, slotsNum, predictMapTaskExecTime, pendingMapTasks, (int)(remainingTime / (predictMapTaskExecTime * 1000)) * slotsNum);
//      i++;      
    }

/*    for (i = 0; i < taskNums.length; i++) {
      totalTaskNums += taskNums[i];
    }*/
  
    if (totalTaskNums >= pendingMapTasks)
      canMeetDeadline = true;
    else
      canMeetDeadline = false;
    
    return canMeetDeadline;  
  
 }

public void initResources() {
   String fileName="/home/hadoop/nodes.txt";
   File file=new File(fileName);
   BufferedReader br = null;
   String node="";
   try {
     br=new BufferedReader(new FileReader(file));
   } catch (FileNotFoundException e) {
     LOG.error("Can not find resource.txt file", e);
   }

   NodeResource nodeResource = new NodeResource(0, 0);
   try {
     while((node=br.readLine())!=null){
        resources.put(node, nodeResource);
     }

  } catch (IOException e1) {
     LOG.error("Exception in reading resource.txt file", e1);
  }

}

 public void updateNodeResources() {
   String fileName="/home/hadoop/resource.txt";
   File file=new File(fileName);
   BufferedReader br = null;
   String host="";
   double cpu=0;
  
   try {
     br=new BufferedReader(new FileReader(file));
   } catch (FileNotFoundException e) {
     LOG.error("Can not find resource.txt file", e);
   } 
     String line=null;
     String[] rec=null;
   try {
   while((line=br.readLine())!=null){
     rec=line.split("\t");
     host=rec[0];
     cpu=Double.parseDouble(rec[1]);
     NodeResource nodeResource = new NodeResource(cpu);
     synchronized (resources) {
       resources.put(host, nodeResource);
     }
     
   }
  } catch (IOException e1) {
    LOG.error("Exception in reading resource.txt file", e1);
  }
 }  

  private class ReceiveThread extends Thread {
    private Socket clientSocket;
    private BufferedReader in;

    public ReceiveThread(Socket s) {
      this.clientSocket = s;
      try {
        this.in = new BufferedReader(new InputStreamReader(this.clientSocket.getInputStream()));
        start();
      } catch(IOException e) {
          LOG.error("Exception in ReceiveThread");
      }
    }
    public void run() {
      String line;
      try {
        while(true) {
          line = in.readLine();
          String[] rec = line.split("\t");
          String host = rec[0];
          double cpu = Double.parseDouble(rec[1]);
          double disk = Double.parseDouble(rec[2]);
          NodeResource nodeResource = new NodeResource(cpu, disk);
          synchronized(resources){
            resources.put(host, nodeResource);
            NodeResource nodeResource1 = resources.get(host);
  //          System.out.printf("???resources address in ReceiveThread():%s %n", Integer.toHexString(System.identityHashCode(resources)));
  //          System.out.printf("+++current time:%d, nodeResource1 address:%s, host:%s, cpu usage:%f, disk:%f %n", System.currentTimeMillis(), 
  //                           Integer.toHexString(System.identityHashCode(nodeResource1)), host, nodeResource1.getCpuUsage(), nodeResource1.getDisk());
          }
        }
      } catch(IOException e) {
        LOG.error("ReceiveThread run() fail to read data from socket");
      } finally {
        if(in != null) {
          try{
            in.close();
          } catch(IOException e1){
            LOG.error("ReceiveThread run() finally block close exception");
          }
        }

      }

    }
  } 

  private class UpdateResourceThread extends Thread {
    private UpdateResourceThread() {
      super("DeadlineScheduler update thread"); 
    }

    public void run() {
      int port = 8888;
      ServerSocket socket = null;
      try {
      JobQueueTaskScheduler jobQueueTaskScheduler = new JobQueueTaskScheduler();
      socket = new ServerSocket(port);
      socket.setReuseAddress(true);
      while(running) {
        Socket clientScoket = socket.accept();
        jobQueueTaskScheduler.new ReceiveThread(clientScoket);
        } 
      } catch (Exception e) {
          LOG.error("Exception in DeadlineScheduler's UpdateThread", e);
      } finally {
        if(socket != null) {
          try {
            socket.close();
          } catch (IOException e1) {
            LOG.error("Exception in UpdateResourceThread run() finally block socket.close()");
          }
        }
      }

     }
    }

}

