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

package edu.uci.ics.asterix.aoya;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.ApplicationMasterProtocol;
import org.apache.hadoop.yarn.api.ContainerManagementProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.StartContainerRequest;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.client.api.async.impl.NMClientAsyncImpl;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;

import edu.uci.ics.asterix.common.configuration.AsterixConfiguration;
import edu.uci.ics.asterix.common.configuration.Coredump;
import edu.uci.ics.asterix.common.configuration.Store;
import edu.uci.ics.asterix.common.configuration.TransactionLogDir;
import edu.uci.ics.asterix.event.schema.yarnCluster.*;

@InterfaceAudience.Public
@InterfaceStability.Unstable
//TODO: this entire file is in danger of falling victim to the 'big ball of mud' antipattern.
public class ApplicationMaster {

    private static final Log LOG = LogFactory.getLog(ApplicationMaster.class);
    private static final String BASE_CONF_PATH = "asterix-server" + File.separator + "conf"+File.separator+"asterix-configuration.xml";
    private static final String CLUSTER_DESC_PATH = "cluster-config.xml";
    private static final String ASTERIX_TAR_PATH = "asterix-server.tar";
    private static final String WORKING_CONF_PATH = "asterix-server" + File.separator + "bin" +File.separator+"asterix-configuration.xml";
    private static final String ASTERIX_CC_BIN_PATH = "asterix-server" + File.separator + "bin" + File.separator + "asterixcc";
    private static final String ASTERIX_NC_BIN_PATH = "asterix-server" + File.separator + "bin" + File.separator + "asterixnc";
    // Configuration
    private Configuration conf;

    // Handle to communicate with the Resource Manager
    @SuppressWarnings("rawtypes")
    private AMRMClientAsync resourceManager;

    // Handle to communicate with the Node Manager
    private NMClientAsync nmClientAsync;
    // Listen to process the response from the Node Manager
    private NMCallbackHandler containerListener;
    // Application Attempt Id ( combination of attemptId and fail count )
    private ApplicationAttemptId appAttemptID;

    // TODO
    // For status update for clients - yet to be implemented
    // Hostname of the container
    private String appMasterHostname = "";
    // Port on which the app master listens for status updates from clients
    private int appMasterRpcPort = 0;
    // Tracking url to which app master publishes info for clients to monitor
    private String appMasterTrackingUrl = "";

    // Counter for completed containers ( complete denotes successful or failed )
    private AtomicInteger numCompletedContainers = new AtomicInteger();
    // Allocated container count so that we know how many containers has the RM
    // allocated to us
    private AtomicInteger numAllocatedContainers = new AtomicInteger();
    // Count of failed containers
    private AtomicInteger numFailedContainers = new AtomicInteger();
    // Count of containers already requested from the RM
    // Needed as once requested, we should not request for containers again.
    // Only request for more if the original requirement changes.
    private AtomicInteger numRequestedContainers = new AtomicInteger();
    //Tells us whether the Cluster Controller is up so we can safely start some Node Controllers
    private AtomicBoolean ccUp = new AtomicBoolean();

    //HDFS path to Asterix distributable tarball
    private String asterixTarPath = "";
    // Timestamp needed for creating a local resource
    private long asterixTarTimestamp = 0;
    // File length needed for local resource
    private long asterixTarLen = 0;

    //HDFS path to Asterix distributable tarball
    private String asterixConfPath = "";
    // Timestamp needed for creating a local resource
    private long asterixConfTimestamp = 0;
    // File length needed for local resource
    private long asterixConfLen = 0;

    private int numTotalContainers = 0;

    private String DFSpathSuffix = "";
    
    // Set the local resources
    private Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();

    private Cluster clusterDesc = null;
    private MasterNode CC = null;


    private volatile boolean done;
    private volatile boolean success;

    // Launch threads
    private List<Thread> launchThreads = new ArrayList<Thread>();

    public static void main(String[] args) {

        boolean result = false;
        try {
            ApplicationMaster appMaster = new ApplicationMaster();
            LOG.info("Initializing ApplicationMaster");
            boolean doRun = appMaster.init(args);
            if (!doRun) {
                System.exit(0);
            }
            result = appMaster.run();
        } catch (Throwable t) {
            LOG.fatal("Error running ApplicationMaster", t);
            System.exit(1);
        }
        if (result) {
            LOG.info("Application Master completed successfully. exiting");
            System.exit(0);
        } else {
            LOG.info("Application Master failed. exiting");
            System.exit(2);
        }
    }

    private void dumpOutDebugInfo() {

        LOG.info("Dump debug output");
        Map<String, String> envs = System.getenv();
        for (Map.Entry<String, String> env : envs.entrySet()) {
            LOG.info("System env: key=" + env.getKey() + ", val=" + env.getValue());
            System.out.println("System env: key=" + env.getKey() + ", val=" + env.getValue());
        }

        String cmd = "ls -al";
        Runtime run = Runtime.getRuntime();
        Process pr = null;
        try {
            pr = run.exec(cmd);
            pr.waitFor();

            BufferedReader buf = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            String line = "";
            while ((line = buf.readLine()) != null) {
                LOG.info("System CWD content: " + line);
                System.out.println("System CWD content: " + line);
            }
            buf.close();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public ApplicationMaster() throws Exception {
        // Set up the configuration and RPC
        conf = new YarnConfiguration();
    }

    public boolean init(String[] args) throws ParseException, IOException {

        Options opts = new Options();
        opts.addOption("app_attempt_id", true, "App Attempt ID. Not to be used unless for testing purposes");
        opts.addOption("priority", true, "Application Priority. Default 0");
        opts.addOption("debug", false, "Dump out debug information");

        opts.addOption("help", false, "Print usage");
        CommandLine cliParser = new GnuParser().parse(opts, args);

        if (args.length == 0) {
            printUsage(opts);
            throw new IllegalArgumentException("No args specified for application master to initialize");
        }

        if (cliParser.hasOption("help")) {
            printUsage(opts);
            return false;
        }

        if (cliParser.hasOption("debug")) {
            dumpOutDebugInfo();
        }

        Map<String, String> envs = System.getenv();

        if (!envs.containsKey(Environment.CONTAINER_ID.name())) {
            if (cliParser.hasOption("app_attempt_id")) {
                String appIdStr = cliParser.getOptionValue("app_attempt_id", "");
                appAttemptID = ConverterUtils.toApplicationAttemptId(appIdStr);
            } else {
                throw new IllegalArgumentException("Application Attempt Id not set in the environment");
            }
        } else {
            ContainerId containerId = ConverterUtils.toContainerId(envs.get(Environment.CONTAINER_ID.name()));
            appAttemptID = containerId.getApplicationAttemptId();
        }

        if (!envs.containsKey(ApplicationConstants.APP_SUBMIT_TIME_ENV)) {
            throw new RuntimeException(ApplicationConstants.APP_SUBMIT_TIME_ENV + " not set in the environment");
        }
        if (!envs.containsKey(Environment.NM_HOST.name())) {
            throw new RuntimeException(Environment.NM_HOST.name() + " not set in the environment");
        }
        if (!envs.containsKey(Environment.NM_HTTP_PORT.name())) {
            throw new RuntimeException(Environment.NM_HTTP_PORT + " not set in the environment");
        }
        if (!envs.containsKey(Environment.NM_PORT.name())) {
            throw new RuntimeException(Environment.NM_PORT.name() + " not set in the environment");
        }

        LOG.info("Application master for app" + ", appId=" + appAttemptID.getApplicationId().getId()
                + ", clustertimestamp=" + appAttemptID.getApplicationId().getClusterTimestamp() + ", attemptId="
                + appAttemptID.getAttemptId());

        asterixTarPath = envs.get(MConstants.TARLOCATION);
        asterixTarTimestamp = Long.parseLong(envs.get(MConstants.TARTIMESTAMP));
        asterixTarLen = Long.parseLong(envs.get(MConstants.TARLEN));

        asterixConfPath = envs.get(MConstants.CONFLOCATION);
        asterixConfTimestamp = Long.parseLong(envs.get(MConstants.CONFTIMESTAMP));
        asterixConfLen = Long.parseLong(envs.get(MConstants.CONFLEN));

        DFSpathSuffix = envs.get(MConstants.PATHSUFFIX);
        LOG.info("Path suffix: "+DFSpathSuffix);
        
        localizeDFSResources();

        try{
        	clusterDesc = parseYarnClusterConfig();
        	CC = clusterDesc.getMasterNode();
        	writeAsterixConfig(clusterDesc);
        }
        catch(FileNotFoundException | JAXBException e){
        	LOG.error("Could not deserialize Cluster Config from disk- aborting!");
        	LOG.error(e);
        	return false;
        }
        
        return true;
    }
    /**
     * Simply parses out the YARN cluster config and instantiates it into a nice object. 
     * @return The object representing the configuration
     * @throws FileNotFoundException 
     * @throws JAXBException
     */
    private Cluster parseYarnClusterConfig() throws FileNotFoundException, JAXBException{
    	//this method just hides away the deserialization silliness
    	File f = new File(CLUSTER_DESC_PATH);
    	JAXBContext configCtx = JAXBContext.newInstance(Cluster.class);
        Unmarshaller unmarshaller = configCtx.createUnmarshaller();
        Cluster cl = (Cluster) unmarshaller.unmarshal(f);	
    	return cl;
    }
    /**
     * 
     */
    private String hostFromContainerID(String containerID){
       return containerID.split("_")[4];
    }
    /**
     * Kanged from managix. Splices the cluster config and asterix config parameters together, then puts the product to HDFS.
     * @param cluster
     * @throws JAXBException
     * @throws FileNotFoundException
     * @throws IOException
     */
    private void writeAsterixConfig(Cluster cluster) throws JAXBException, FileNotFoundException, IOException{
        String metadataNodeId = getMetadataNode(cluster).getId();
        String asterixInstanceName = appAttemptID.toString();

        //this is the "base" config that is inside the tarball, we start here
        AsterixConfiguration configuration = loadBaseAsterixConfig();

        configuration.setInstanceName(asterixInstanceName);

        String storeDir = null;
        List<Store> stores = new ArrayList<Store>();
        for (Node node : cluster.getNode()) {
            storeDir = node.getStore() == null ? cluster.getStore() : node.getStore();
            stores.add(new Store(node.getId(), storeDir));
        }
        configuration.setStore(stores);

        List<Coredump> coredump = new ArrayList<Coredump>();
        String coredumpDir = null;
        List<TransactionLogDir> txnLogDirs = new ArrayList<TransactionLogDir>();
        String txnLogDir = null;
        for (Node node : cluster.getNode()) {
            coredumpDir = node.getLogDir() == null ? cluster.getLogDir() : node.getLogDir();
            coredump.add(new Coredump(node.getId(), coredumpDir +  "coredump"+File.separator));
            txnLogDir = node.getTxnLogDir() == null ? cluster.getTxnLogDir() : node.getTxnLogDir();
            txnLogDirs.add(new TransactionLogDir(node.getId(), txnLogDir+"txnLogs"+File.separator));
        }
        configuration.setMetadataNode(metadataNodeId);

        configuration.setCoredump(coredump);
        configuration.setTransactionLogDir(txnLogDirs);

        JAXBContext ctx = JAXBContext.newInstance(AsterixConfiguration.class);
        Marshaller marshaller = ctx.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        FileOutputStream os = new FileOutputStream(WORKING_CONF_PATH);
        marshaller.marshal(configuration, os);
        os.close();	
        //now put this to HDFS so our NCs and CC can use it. 
        FileSystem fs = FileSystem.get(conf);
        File srcfile = new File(WORKING_CONF_PATH);
        Path src = new Path(srcfile.getCanonicalPath());
        String pathSuffix = DFSpathSuffix + File.separator + "asterix-configuration.xml";
        Path dst = new Path(fs.getHomeDirectory(), pathSuffix);
        fs.copyFromLocalFile(false, true, src, dst);
        URI paramLocation = dst.toUri();
        FileStatus paramFileStatus = fs.getFileStatus(dst);
        Long paramLen = paramFileStatus.getLen();
        Long paramTimestamp = paramFileStatus.getModificationTime();
        LocalResource asterixParamLoc = Records.newRecord(LocalResource.class);
        asterixParamLoc.setType(LocalResourceType.FILE);
        asterixParamLoc.setVisibility(LocalResourceVisibility.PUBLIC);
        asterixParamLoc.setResource(ConverterUtils.getYarnUrlFromURI(paramLocation));
        asterixParamLoc.setTimestamp(paramTimestamp);
        asterixParamLoc.setSize(paramLen);
        localResources.put("asterix-configuration.xml", asterixParamLoc);

    }
    
    private void unTarAsterixConf() throws IOException{
    	LOG.info("Untarring "+ ASTERIX_TAR_PATH);
    	File tarball = new File(ASTERIX_TAR_PATH);
    	if(!tarball.isFile()){
    		LOG.error("Tar not found!");
    		return;
    	}
    	TarArchiveInputStream tar = new TarArchiveInputStream(new FileInputStream(tarball) );
    	TarArchiveEntry current = tar.getNextTarEntry();
    	//make the extraction dir...
    	new File("asterix-server/").mkdir();
    	while(current != null ){
    		LOG.info("Extracting File: "+current.getName());
    		File output = new File(new File("./asterix-server").getCanonicalPath()+File.separator+current.getName());
    		if(output.exists()){
    			LOG.info("File exists: "+output.getName());
    			current = tar.getNextTarEntry();
    			continue;
    		}
    		//decide if we want to write this or not...
    		if(current.isDirectory()){
    			if(!output.mkdirs())LOG.info("Couldn't make directory: " + output.getCanonicalPath());;
    			current = tar.getNextTarEntry();
    			continue;
    		}
    		if(!output.getParentFile().exists()) output.getParentFile().mkdirs();
    		OutputStream os = new FileOutputStream(output);
    		IOUtils.copy(tar, os);
    		LOG.info("Untarred "+ output.getName());
    		os.close();
            current = tar.getNextTarEntry();
    	}
    	tar.close();
    }
    /**
     * Loads the basic Asterix configuration file that is embedded in the 
     * distributable tar file 
     * 
     * @return A skeleton Asterix configuration
     *
     */
    
    private AsterixConfiguration loadBaseAsterixConfig() throws IOException, JAXBException {
    	//now oddly enough, at the AM, we won't have this file un-tarred even though i said it was an archive (go figure)
    	//so let's do that 
    	unTarAsterixConf();
    	File f = new File(WORKING_CONF_PATH);
    	JAXBContext configCtx = JAXBContext.newInstance(AsterixConfiguration.class);
        Unmarshaller unmarshaller = configCtx.createUnmarshaller();
        AsterixConfiguration conf = (AsterixConfiguration) unmarshaller.unmarshal(f);	
    	return conf;
    }
    /**
     * 
     * @param c The cluster exception to attempt to alocate with the RM
     * @throws YarnException
     */
    private void requestResources(Cluster c)throws YarnException,UnknownHostException{
        //request CC
        int numNodes = 0;
        ContainerRequest ccAsk = askForHost(CC.getClusterIp());
        resourceManager.addContainerRequest(ccAsk);
        LOG.info("Asked for CC: " + CC.getClusterIp());
        numNodes++;
        //now we wait to be given the CC before starting the NCs...
        //we will wait a minute. 
        int death_clock = 60;
        while (ccUp.get() == false && death_clock>0) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {

            }
            --death_clock;
        }
        if(death_clock == 0 && ccUp.get() == false){
        	throw new YarnException("Couldn't allocate container for CC. Abort!");
        }
        LOG.info("Waiting for CC process to start");
        //TODO: inspect for actual liveness instead of waiting.
        // is there a good way to do this? maybe try opening a socket to it...
        try {
            Thread.sleep(10000);
        } catch (InterruptedException ex) {

        }
        //request NCs
        for (Node n : c.getNode()) {
            resourceManager.addContainerRequest(askForHost(n.getClusterIp()));
            LOG.info("Asked for NC: " + n.getClusterIp());
            numNodes++;
        }
        LOG.info("Requested all NCs and CCs. Wait for things to settle!");
        numRequestedContainers.set(numNodes);
        numTotalContainers = numNodes;

    }

    /**
    * Asks the RM for a particular host, nicely.  
    * @param host The host to request
    * @return A container request that is (hopefully) for the host we asked for. 
    */
    private ContainerRequest askForHost(String host) throws UnknownHostException {
    	InetAddress hostIp = InetAddress.getByName(host);
        Priority pri = Records.newRecord(Priority.class);
        pri.setPriority(0);
        Resource capability = Records.newRecord(Resource.class);
        capability.setMemory(768);
        //we dont set anything because we don't care about that
        String[] hosts = new String[1];
        //TODO this is silly
        hosts[0] = hostIp.getHostName().split("\\.")[0];
        LOG.info("IP addr: "+host+" resolved to "+hostIp.getHostName());
        // last flag must be false (relaxLocality)
        //changed for testing?????
        ContainerRequest request = new ContainerRequest(capability, hosts, null, pri, false);
        LOG.info("Requested host ask: " + request.getNodes());
        return request;
    }
    
    /**
     * Writes an Asterix configuration based on the data inside the cluster description
     * @param cluster
     * @throws JAXBException
     * @throws FileNotFoundException
     */
    
    private Node getMetadataNode(Cluster cluster) {
        Node metadataNode = null;
        if (cluster.getMetadataNode() != null) {
            for (Node node : cluster.getNode()) {
                if (node.getId().equals(cluster.getMetadataNode())) {
                    metadataNode = node;
                    break;
                }
            }
        } else {
            Random random = new Random();
            int nNodes = cluster.getNode().size();
            metadataNode = cluster.getNode().get(random.nextInt(nNodes));
        }
        return metadataNode;
    }

    /**
     * Here I am just moving all of the resources from HDFS to the local
     * filesystem of the nodes. 
     */
    private void localizeDFSResources(){

        LocalResource asterixTar = Records.newRecord(LocalResource.class);

        //this un-tar's the asterix distribution
        asterixTar.setType(LocalResourceType.ARCHIVE);

        // Set visibility of the resource
        // Setting to most private option
        asterixTar.setVisibility(LocalResourceVisibility.APPLICATION);
        // Set the resource to be copied over
        try {
            asterixTar.setResource(ConverterUtils.getYarnUrlFromURI(new URI(asterixTarPath)));

        } catch (URISyntaxException e) {
            LOG.error("Error locating Asterix tarball" + " in env, path=" + asterixTarPath);
            e.printStackTrace();
        }
        // Set timestamp and length of file so that the framework
        // can do basic sanity checks for the local resource
        // after it has been copied over to ensure it is the same
        // resource the client intended to use with the application
        LOG.debug("DSConstants Jar: " + MConstants.TARLOCATION);
        LOG.debug("DSConstants Size: " + MConstants.TARTIMESTAMP);
        LOG.debug("DSConstants TimeStamp: " + MConstants.TARLEN);

        asterixTar.setTimestamp(asterixTarTimestamp);
        asterixTar.setSize(asterixTarLen);
        localResources.put("asterix-server", asterixTar);
        

        //now let's do the same for the cluster description XML
        LocalResource asterixConf = Records.newRecord(LocalResource.class);
        asterixConf.setType(LocalResourceType.FILE);

        asterixConf.setVisibility(LocalResourceVisibility.APPLICATION);
        try {
            asterixConf.setResource(ConverterUtils.getYarnUrlFromURI(new URI(asterixConfPath)));

        } catch (URISyntaxException e) {
            LOG.error("Error locating Asterix config" + " in env, path=" + asterixConfPath);
            e.printStackTrace();
        }
        LOG.debug("DSConstants Jar: " + MConstants.CONFLOCATION);
        LOG.debug("DSConstants Size: " + MConstants.CONFTIMESTAMP);
        LOG.debug("DSConstants TimeStamp: " + MConstants.CONFLEN);

        asterixConf.setTimestamp(asterixConfTimestamp);
        asterixConf.setSize(asterixConfLen);
        localResources.put("cluster-config.xml", asterixConf);
    }
    private void printUsage(Options opts) {
        new HelpFormatter().printHelp("ApplicationMaster", opts);
    }

    @SuppressWarnings({ "unchecked" })
    /**
     * Start the AM and request all necessary resources. 
     * @return True if the run fully succeeded, false otherwise. 
     * @throws YarnException
     * @throws IOException
     */
    public boolean run() throws YarnException, IOException {
        LOG.info("Starting ApplicationMaster");

        AMRMClientAsync.CallbackHandler allocListener = new RMCallbackHandler();
        resourceManager = AMRMClientAsync.createAMRMClientAsync(1000, allocListener);
        resourceManager.init(conf);
        resourceManager.start();

        containerListener = new NMCallbackHandler();
        nmClientAsync = new NMClientAsyncImpl(containerListener);
        nmClientAsync.init(conf);
        nmClientAsync.start();

        // Register self with ResourceManager
        // This will start heartbeating to the RM
        try{
            appMasterHostname = InetAddress.getLocalHost().toString();
        }
        catch(java.net.UnknownHostException uhe){
            appMasterHostname = uhe.toString();
        }
        RegisterApplicationMasterResponse response = resourceManager.registerApplicationMaster(appMasterHostname,
                appMasterRpcPort, appMasterTrackingUrl);
        // Dump out information about cluster capability as seen by the
        // resource manager
        int maxMem = response.getMaximumResourceCapability().getMemory();
        LOG.info("Max mem capabililty of resources in this cluster " + maxMem);

        try{
        	requestResources(clusterDesc);
        }
        catch(YarnException e){
        	LOG.error("Could not allocate resources properly:" + e.getMessage());
        	done = true;
        }
        //now we just sit and listen for messages from the RM
        
        while (!done) {
        	try {
        		Thread.sleep(200);
        	} catch (InterruptedException ex) {}
        }
        finish();
        return success;
    }
    /**
     * Clean up, whether or not we were successful. 
     */
    private void finish() {
        // Join all launched threads
        // needed for when we time out
        // and we need to release containers
        for (Thread launchThread : launchThreads) {
            try {
                launchThread.join(10000);
            } catch (InterruptedException e) {
                LOG.info("Exception thrown in thread join: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // When the application completes, it should stop all running containers
        LOG.info("Application completed. Stopping running containers");
        nmClientAsync.stop();

        // When the application completes, it should send a finish application
        // signal to the RM
        LOG.info("Application completed. Signalling finish to RM");

        FinalApplicationStatus appStatus;
        String appMessage = null;
        success = true;
        if (numFailedContainers.get() == 0 && numCompletedContainers.get() == numTotalContainers) {
            appStatus = FinalApplicationStatus.SUCCEEDED;
        } else {
            appStatus = FinalApplicationStatus.FAILED;
            appMessage = "Diagnostics." + ", total=" + numTotalContainers + ", completed="
                    + numCompletedContainers.get() + ", allocated=" + numAllocatedContainers.get() + ", failed="
                    + numFailedContainers.get();
            success = false;
        }
        try {
            resourceManager.unregisterApplicationMaster(appStatus, appMessage, null);
        } catch (YarnException ex) {
            LOG.error("Failed to unregister application", ex);
        } catch (IOException e) {
            LOG.error("Failed to unregister application", e);
        }
        done = true;
        resourceManager.stop();
    }
    /**
     * This handles the information that comes in from the RM while the AM
     * is running. 
     */
    private class RMCallbackHandler implements AMRMClientAsync.CallbackHandler {
        @SuppressWarnings("unchecked")
        public void onContainersCompleted(List<ContainerStatus> completedContainers) {
            LOG.info("Got response from RM for container ask, completedCnt=" + completedContainers.size());
            for (ContainerStatus containerStatus : completedContainers) {
                LOG.info("Got container status for containerID=" + containerStatus.getContainerId() + ", state="
                        + containerStatus.getState() + ", exitStatus=" + containerStatus.getExitStatus()
                        + ", diagnostics=" + containerStatus.getDiagnostics());

                // non complete containers should not be here
                assert (containerStatus.getState() == ContainerState.COMPLETE);

                // increment counters for completed/failed containers
                int exitStatus = containerStatus.getExitStatus();
                if (0 != exitStatus) {
                    // container failed
                    if (ContainerExitStatus.ABORTED != exitStatus) {
                        // shell script failed
                        // counts as completed
                        numCompletedContainers.incrementAndGet();
                        numFailedContainers.incrementAndGet();
                    } else {
                        // container was killed by framework, possibly preempted
                        // we should re-try as the container was lost for some reason
                        numAllocatedContainers.decrementAndGet();
                        numRequestedContainers.decrementAndGet();
                        // we do not need to release the container as it would be done
                        // by the RM
                    }
                } else {
                    // nothing to do
                    // container completed successfully
                    numCompletedContainers.incrementAndGet();
                    LOG.info("Container completed successfully." + ", containerId=" + containerStatus.getContainerId());
                }
            }
            //stop infinite looping of run()
            done = true;
        }

        public void onContainersAllocated(List<Container> allocatedContainers) {
            LOG.info("Got response from RM for container ask, allocatedCnt=" + allocatedContainers.size());
            numAllocatedContainers.addAndGet(allocatedContainers.size());
            for (Container allocatedContainer : allocatedContainers) {
                LOG.info("Launching shell command on a new container." + ", containerId=" + allocatedContainer.getId()
                        + ", containerNode=" + allocatedContainer.getNodeId().getHost() + ":"
                        + allocatedContainer.getNodeId().getPort() + ", containerNodeURI="
                        + allocatedContainer.getNodeHttpAddress() + ", containerResourceMemory"
                        + allocatedContainer.getResource().getMemory());

                LaunchContainerRunnable runnableLaunchContainer = new LaunchContainerRunnable(allocatedContainer,
                        containerListener);
                Thread launchThread = new Thread(runnableLaunchContainer);

                // I want to know if this node is the CC, because it must start before the NCs. 
                if (allocatedContainer.getNodeId().getHost().equals(CC.getId())) {
                    ccUp.set(true);
                }
                // launch and start the container on a separate thread to keep
                // the main thread unblocked
                // as all containers may not be allocated at one go.
                launchThreads.add(launchThread);
                launchThread.start();
            }
        }
        /**
         * Ask the processes on the container to gracefully exit. 
         */
        public void onShutdownRequest() {
        	LOG.info("AM shutting down per request");
            done = true;
        }

        public void onNodesUpdated(List<NodeReport> updatedNodes) {
        	//TODO: This will become important when we deal with what happens if an NC dies
        }

        public float getProgress() {
        //return half way because progress is basically meaningless for us
            return (float) 0.5;
        }

        public void onError(Throwable arg0) {
        	LOG.error("Fatal Error recieved by AM: "+arg0);
            done = true;
        }
    }

    private class NMCallbackHandler implements NMClientAsync.CallbackHandler {

        private ConcurrentMap<ContainerId, Container> containers = new ConcurrentHashMap<ContainerId, Container>();

        public void addContainer(ContainerId containerId, Container container) {
            containers.putIfAbsent(containerId, container);
        }

        public void onContainerStopped(ContainerId containerId) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Succeeded to stop Container " + containerId);
            }
            containers.remove(containerId);
        }

        public void onContainerStatusReceived(ContainerId containerId, ContainerStatus containerStatus) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Container Status: id=" + containerId + ", status=" + containerStatus);
            }
        }

        public void onContainerStarted(ContainerId containerId, Map<String, ByteBuffer> allServiceResponse) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Succeeded to start Container " + containerId);
            }
            Container container = containers.get(containerId);
            if (container != null) {
                nmClientAsync.getContainerStatusAsync(containerId, container.getNodeId());
            }
        }

        public void onStartContainerError(ContainerId containerId, Throwable t) {
            LOG.error("Failed to start Container " + containerId);
            containers.remove(containerId);
        }

        public void onGetContainerStatusError(ContainerId containerId, Throwable t) {
            LOG.error("Failed to query the status of Container " + containerId);
        }

        public void onStopContainerError(ContainerId containerId, Throwable t) {
            LOG.error("Failed to stop Container " + containerId);
            containers.remove(containerId);
        }
    }

    /**
     * Thread to connect to the {@link ContainerManagementProtocol} and launch the container
     * that will execute the shell command.
     */
    private class LaunchContainerRunnable implements Runnable {

        // Allocated container
        Container container;

        NMCallbackHandler containerListener;

        /**
         * @param lcontainer
         *            Allocated container
         * @param containerListener
         *            Callback handler of the container
         */
        public LaunchContainerRunnable(Container lcontainer, NMCallbackHandler containerListener) {
            this.container = lcontainer;
            this.containerListener = containerListener;
        }

        /**
         * Connects to CM, sets up container launch context
         * for shell command and eventually dispatches the container
         * start request to the CM.
         */
        public void run() {
            LOG.info("Setting up container launch container for containerid=" + container.getId());
            ContainerLaunchContext ctx = Records.newRecord(ContainerLaunchContext.class);
            // Set the local resources
            ctx.setLocalResources(localResources);

            //Set the env variables to be setup in the env where the application master will be run
            LOG.info("Set the environment for the node");
            Map<String, String> env = new HashMap<String, String>();

            // Add AppMaster.jar location to classpath
            // At some point we should not be required to add
            // the hadoop specific classpaths to the env.
            // It should be provided out of the box.
            // For now setting all required classpaths including
            // the classpath to "." for the application jar
            StringBuilder classPathEnv = new StringBuilder(Environment.CLASSPATH.$()).append(File.pathSeparatorChar)
                    .append("./*");
            for (String c : conf.getStrings(YarnConfiguration.YARN_APPLICATION_CLASSPATH,
                    YarnConfiguration.DEFAULT_YARN_APPLICATION_CLASSPATH)) {
                classPathEnv.append(File.pathSeparatorChar);
                classPathEnv.append(c.trim());
            }
            classPathEnv.append(File.pathSeparatorChar).append("./log4j.properties");

            // add the runtime classpath needed for tests to work
            if (conf.getBoolean(YarnConfiguration.IS_MINI_YARN_CLUSTER, false)) {
                classPathEnv.append(':');
                classPathEnv.append(System.getProperty("java.class.path"));
            }

            env.put("CLASSPATH", classPathEnv.toString());

            ctx.setEnvironment(env);
            LOG.info(ctx.getEnvironment().toString());
            /*
            try{
            writeAsterixConfig(clusterDesc);
            }
            catch (JAXBException | IOException e){
            	LOG.error("Couldn't write properites config file to disk.");
            	e.printStackTrace();
            	return;
            }
            */
            List<String> startCmd = produceStartCmd(container);
            for(String s :startCmd){
            	LOG.info("Command to execute: "+s);
            }
            ctx.setCommands(startCmd);
            containerListener.addContainer(container.getId(), container);
            //finally start the container!?
            nmClientAsync.startContainerAsync(container, ctx);
        }
        /**
         * Determines for a given container what the necessary command line
         * arguments are to start the Asterix processes on that instance
         * 
         * @param container The container to produce the commands for
         * @return A list of the commands that should be executed
         */
        private List<String> produceStartCmd(Container container){
        	//TODO: stop using export! bad programmer, no coffee
            List<String> commands = new ArrayList<String>();
            // Set the necessary command to execute on the allocated container
            Vector<CharSequence> vargs = new Vector<CharSequence>(5);

            //first see if this node is the CC
            if(containerIsCC(container)){
            	LOG.info("CC found on container" + container.getNodeId().getHost());
                vargs.add("JAVA_OPTS=-Xmx768m "+
                          ASTERIX_CC_BIN_PATH+" -cluster-net-ip-address "+ CC.getClusterIp() +
                		  " -client-net-ip-address "+ CC.getClientIp()
                		 );
                
            }
            //now we need to know what node we are on, so we can apply the correct properties

            Node local;
            try{
            	local = containerToNode(container, clusterDesc);
                LOG.info("Attempting to start NC on host " + local.getId());
                vargs.add("export JAVA_OPTS=-Xmx1536m -DAsterixConfigFileName="+WORKING_CONF_PATH+";");
                vargs.add(ASTERIX_NC_BIN_PATH+" -node-id "+ local.getId());
                vargs.add("-cc-host "+ CC.getClusterIp());
                vargs.add("-iodevices "+local.getIodevices());
                vargs.add("-cluster-net-ip-address "+local.getClusterIp());
                vargs.add("-data-ip-address "+CC.getClusterIp());
                vargs.add("-result-ip-address "+CC.getClusterIp());
            }
            catch(UnknownHostException e){
            	LOG.error("Unable to find NC configured for host: "+
            			   container.getId() + e.getStackTrace());
            }
            
            // Add log redirect params
            vargs.add("1>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stdout");
            vargs.add("2>" + ApplicationConstants.LOG_DIR_EXPANSION_VAR + "/stderr");

            // Get final commmand
            StringBuilder command = new StringBuilder();
            for (CharSequence str : vargs) {
                command.append(str).append(" ");
            }
            commands.add(command.toString());
            return commands;
        }
        /**
         * Attempts to find the Node in the Cluster Description that matches this container
         * @param c The container to resolve
         * @return The node this container corresponds to
         * @throws java.net.UnknownHostException if the container isn't present in the description
         */
        private Node containerToNode(Container c, Cluster cl) throws UnknownHostException{
            String containerHost = c.getNodeId().getHost();
            InetAddress containerIp = InetAddress.getByName(containerHost);
            for (Node node : cl.getNode()) {
            	InetAddress nodeIp = InetAddress.getByName(node.getClusterIp());
            	if(nodeIp == containerIp) return node;
            }
            //if we find nothing, this is bad...
            throw new java.net.UnknownHostException("Could not resolve container to node");
        }
        /**
         * Determines whether or not a container is the one on which the CC should reside
         * @param c The container in question
         * @return True if the container should have the CC process on it, false otherwise.
         */
        private boolean containerIsCC(Container c){
            String containerHost = c.getNodeId().getHost();
            try{
            	InetAddress containerIp = InetAddress.getByName(containerHost);
            	InetAddress ccIp = InetAddress.getByName(CC.getClusterIp());
            	return containerIp.equals(ccIp);
            }
            catch(UnknownHostException e){
            	return false;
            }
        }
        
    }
}
