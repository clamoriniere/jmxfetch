package org.datadog.jmxfetch;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import javax.security.auth.login.FailedLoginException;

import org.apache.log4j.Appender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.commons.lang3.CharEncoding;
import org.datadog.jmxfetch.reporter.Reporter;
import org.datadog.jmxfetch.util.CustomLogger;
import org.datadog.jmxfetch.util.FileHelper;


import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.common.primitives.Bytes;


@SuppressWarnings("unchecked")
public class App {
    private final static Logger LOGGER = Logger.getLogger(App.class.getName());
    private final static String AUTO_DISCOVERY_PREFIX = "AD-";
    public static final String CANNOT_CONNECT_TO_INSTANCE = "Cannot connect to instance ";
    private static final String AD_CONFIG_SEP = "#### AUTO-DISCOVERY ####";
    private static final String AD_LEGACY_CONFIG_SEP = "#### SERVICE-DISCOVERY ####";
    private static final String AD_CONFIG_TERM = "#### AUTO-DISCOVERY TERM ####";
    private static final String AD_LEGACY_CONFIG_TERM = "#### SERVICE-DISCOVERY TERM ####";
    private static int backoffLow = 1;
    private static int backoffHigh = 1000;
    private static int maxRetry = 4;
    private static int loopCounter;
    Random rand = new Random();
    private AtomicBoolean reinit = new AtomicBoolean(false);
    private ConcurrentHashMap<String, YamlParser> configs;
    private ConcurrentHashMap<String, YamlParser> adConfigs = new ConcurrentHashMap<String, YamlParser>();
    private ArrayList<Instance> instances = new ArrayList<Instance>();
    private LinkedList<Instance> brokenInstances = new LinkedList<Instance>();
    private AppConfig appConfig;


    public App(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.configs = getConfigs(appConfig);
    }

    /**
     * Main entry of JMXFetch
     * <p/>
     * See AppConfig class for more details on the args
     */
    public static void main(String[] args) {

        // Load the config from the args
        AppConfig config = new AppConfig();
        JCommander jCommander = null;
        try {
            // Try to parse the args using JCommander
            jCommander = new JCommander(config, args);
        } catch (ParameterException e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }

        // Display the help and quit
        if (config.isHelp() || config.getAction().equals(AppConfig.ACTION_HELP)) {
            jCommander.usage();
            System.exit(0);
        }

        // Set up the logger to add file handler
        CustomLogger.setup(Level.toLevel(config.getLogLevel()), config.getLogLocation());


        // The specified action is unknown
        if (!AppConfig.ACTIONS.contains(config.getAction())) {
            LOGGER.fatal(config.getAction() + " is not in " + AppConfig.ACTIONS + ". Exiting.");
            System.exit(1);
        }

        // The "list_*" actions can only be used with the reporter
        if (!config.getAction().equals(AppConfig.ACTION_COLLECT) && !config.isConsoleReporter()) {
            LOGGER.fatal(config.getAction() + " argument can only be used with the console reporter. Exiting.");
            System.exit(1);
        }

        if(config.getAction().equals(AppConfig.ACTION_LIST_JVMS)) {
            List<com.sun.tools.attach.VirtualMachineDescriptor> descriptors = com.sun.tools.attach.VirtualMachine.list();

            System.out.println("List of JVMs for user " + System.getProperty("user.name") );
            for(com.sun.tools.attach.VirtualMachineDescriptor descriptor : descriptors) {
                System.out.println( "\tJVM id " + descriptor.id() + ": '" + descriptor.displayName() + "'" );
            }
            System.exit(0);
        }

        // Set up the shutdown hook to properly close resources
        attachShutdownHook();

        LOGGER.info("JMX Fetch has started");

        App app = new App(config);

        // Initiate JMX Connections, get attributes that match the yaml configuration
        app.init(false);

        // We don't want to loop if the action is list_* as it's just used for display information about what will be collected
        if (config.getAction().equals(AppConfig.ACTION_COLLECT)) {
            // Start the main loop
            app.start();
        }
    }

    /**
     * Attach a Shutdown Hook that will be called when SIGTERM is sent to JMXFetch
     */
    private static void attachShutdownHook() {
        class ShutdownHook {
            public void attachShutDownHook() {
                Runtime.getRuntime().addShutdownHook(new Thread() {
                    @Override
                    public void run() {
                        LOGGER.info("JMXFetch is closing");
                        // Properly close log handlers
                        Enumeration<Appender> enume = (Enumeration<Appender>) LOGGER.getAllAppenders();
                        while (enume.hasMoreElements()) {
                            Appender h = enume.nextElement();
                            h.close();
                        }
                    }
                });
            }
        }
        new ShutdownHook().attachShutDownHook();
    }

    public void setReinit(boolean reinit) {
        this.reinit.set(reinit);
    }

    public static int getLoopCounter() {
        return loopCounter;
    }

    private static void clearInstances(List<Instance> instances) {
        Iterator<Instance> iterator = instances.iterator();
        while (iterator.hasNext()) {
            Instance instance = iterator.next();
            instance.cleanUp();
            iterator.remove();
        }
    }

    private String getAutoDiscoveryName(String config){
      String[] splitted = config.split(System.getProperty("line.separator"), 2);

      return AUTO_DISCOVERY_PREFIX + splitted[0].substring(2, splitted[0].length());
    }

    private FileInputStream newAutoDiscoveryPipe() {
        FileInputStream adPipe = null;
        String pipeName = appConfig.getAutoDiscoveryPipe();
        try {
            adPipe = new FileInputStream(pipeName);
            LOGGER.info("Named pipe for Auto-Discovery opened: " + pipeName);
        } catch (FileNotFoundException e) {
            LOGGER.info("Unable to open named pipe for Auto-Discovery: " + pipeName);
        }

        return adPipe;
    }

    public boolean processAutoDiscovery(byte[] buffer) {
      boolean reinit = false;
      String[] discovered;

      try {
        String configs = new String(buffer, CharEncoding.UTF_8);
        String separator = App.AD_CONFIG_SEP;

        if (configs.indexOf(App.AD_LEGACY_CONFIG_SEP) != -1) {
            separator = App.AD_LEGACY_CONFIG_SEP;
        }
        discovered = configs.split(separator + System.getProperty("line.separator"));
      } catch(UnsupportedEncodingException e) {
        LOGGER.debug("Unable to parse byte buffer to UTF-8 String.");
        return false;
      }

      for (String config : discovered) {
        if (config == null || config.isEmpty()) {
          continue;
        }

        try{
          String name = getAutoDiscoveryName(config);
          LOGGER.debug("Attempting to apply config. Name: " + name + "\nconfig: \n" + config);
          InputStream stream = new ByteArrayInputStream(config.getBytes(CharEncoding.UTF_8));
          YamlParser yaml = new YamlParser(stream);

          if (this.addConfig(name, yaml)){
            reinit = true;
            LOGGER.debug("Configuration added succesfully reinit in order");
          }
        } catch(UnsupportedEncodingException e) {
          LOGGER.debug("Unable to parse byte buffer to UTF-8 String.");
        }
      }

      return reinit;
    }

    void start() {
        // Main Loop that will periodically collect metrics from the JMX Server
        long start_ms = System.currentTimeMillis();
        long delta_s = 0;
        FileInputStream adPipe = null;

        if(appConfig.getAutoDiscoveryEnabled()) {
            LOGGER.info("Auto Discovery enabled");
            adPipe = newAutoDiscoveryPipe();
            try {
                FileHelper.touch(new File(appConfig.getJMXLaunchFile()));
            } catch (IOException e) {
                  LOGGER.warn("Unable to create launch file"
                          + " - Auto-Discovery configs will not be automatically resubmitted.");
            }
        }

        while (true) {
            // Exit on exit file trigger...
            if (appConfig.getExitWatcher().shouldExit()){
                LOGGER.info("Exit file detected: stopping JMXFetch.");
                System.exit(0);
            }

            // any SD configs waiting in pipe?
            if(adPipe == null && appConfig.getAutoDiscoveryEnabled()) {
                // If SD is enabled and the pipe is not open, retry opening pipe
                adPipe = newAutoDiscoveryPipe();
            }
            try {
                if(adPipe != null && adPipe.available() > 0) {
                    byte[] buffer = new byte[0];
                    boolean terminated = false;
                    while (!terminated) {
                        int len = adPipe.available();
                        if (len > 0) {
                            byte[] minibuff = new byte[len];
                            adPipe.read(minibuff);

                            // The separator always comes in its own atomic write() from the agent side -
                            // so it will never be chopped.
                            if (Bytes.indexOf(minibuff, App.AD_LEGACY_CONFIG_TERM.getBytes()) > -1 ||
                                    Bytes.indexOf(minibuff, App.AD_CONFIG_TERM.getBytes()) > -1 ) {
                                terminated = true;
                            }

                            // make room for read chunk
                            int oldLen = buffer.length;
                            buffer = Arrays.copyOf(buffer, buffer.length + len);
                            System.arraycopy(minibuff, 0, buffer, oldLen, len);
                        }
                    }
                    setReinit(processAutoDiscovery(buffer));
                }
            } catch(IOException e) {
              LOGGER.warn("Unable to read from pipe - Service Discovery configuration may have been skipped.");
            } catch(Exception e) {
              LOGGER.warn("Unknown problem parsing auto-discovery configuration: " + e);
            }

            long start = System.currentTimeMillis();
            if (this.reinit.get()) {
                init(true);
            }

            if (instances.size() > 0) {
                doIteration();
            } else {
                LOGGER.warn("No instance could be initiated. Retrying initialization.");
                appConfig.getStatus().flush();
                configs = getConfigs(appConfig);
                init(true);
            }
            long length = System.currentTimeMillis() - start;
            LOGGER.debug("Iteration ran in " + length + " ms");
            // Sleep until next collection
            try {
                int loopPeriod = appConfig.getCheckPeriod();
                LOGGER.debug("Sleeping for " + loopPeriod + " ms.");
                Thread.sleep(loopPeriod);
            } catch (InterruptedException e) {
                LOGGER.warn(e.getMessage(), e);
            }
        }
    }

    public void doIteration() {
        loopCounter++;
        Reporter reporter = appConfig.getReporter();
        ArrayList<Instance> retryInstances = new ArrayList<Instance>();
        ArrayList<Instance> runInstances = new ArrayList<Instance>();
		Iterator<Instance> it;
        
        for (int tryN = 0; tryN <= 4; tryN++) {
        		if (tryN == 0) {
        			it = instances.iterator();
        		} else if (retryInstances.size() > 0) {
        			// Make a copy and reset so retryInstances can be filled up again
        			runInstances = new ArrayList<Instance>(retryInstances);
        			retryInstances = new ArrayList<Instance>();
        			it = runInstances.iterator();
                if (tryN < maxRetry) {
                		int backoff = (int) Math.round(Math.pow(2, tryN) * 1000);
                		backoff += rand.nextInt(backoffHigh) + backoffLow;
                		try {
							Thread.sleep(backoff);
						} catch (InterruptedException e1) {
							LOGGER.warn("Sleeping thread interrupted unexpectedly");
						}
                }
        		} else {
        			break;
        		}
        		
	        while (it.hasNext()) {
	            Instance instance = it.next();
	            LinkedList<HashMap<String, Object>> metrics;
	            String instanceStatus = Status.STATUS_OK;
	            String scStatus = Status.STATUS_OK;
	            String instanceMessage = null;
	            int numberOfMetrics = 0;  

                try {
                	if (!instance.timeToCollect()) {
                		LOGGER.debug("it is not time to collect, skipping run for " + instance.getName());
                		continue;
                	}

                    metrics = instance.getMetrics();
                    numberOfMetrics = metrics.size();

                    if (numberOfMetrics == 0) {
                        instanceMessage = "Instance " + instance + " didn't return any metrics";
                        LOGGER.warn(instanceMessage);
                        instanceStatus = Status.STATUS_ERROR;
                        scStatus = Status.STATUS_ERROR;
                        brokenInstances.add(instance);
                    } else if (instance.isLimitReached()) {
                        instanceMessage = "Number of returned metrics is too high for instance: "
                                + instance.getName()
                                + ". Please read http://docs.datadoghq.com/integrations/java/ or get in touch with Datadog "
                                + "Support for more details. Truncating to " + instance.getMaxNumberOfMetrics() + " metrics.";

                        instanceStatus = Status.STATUS_WARNING;
                        // We don't want to log the warning at every iteration so we use this custom logger.
                        CustomLogger.laconic(LOGGER, Level.WARN, instanceMessage, 0);
                    }

                    if(numberOfMetrics > 0)
                        reporter.sendMetrics(metrics, instance.getName());
                    
                } catch (IOException e) {
                    instanceMessage = "Unable to refresh bean list for instance " + instance;
                    if (tryN < maxRetry) {
                    		instanceMessage += " queuing for a retry";
                    		// add the failed instance if there are more to add
                    		retryInstances.add(instance);
                    } else {
                    		// Only declare it broken once it's run (and failed) four times
                    		brokenInstances.add(instance);
                    }
                    LOGGER.warn(instanceMessage, e);
                    instanceStatus = Status.STATUS_ERROR;
                    scStatus = Status.STATUS_ERROR;  
                }
                this.reportStatus(appConfig, reporter, instance, numberOfMetrics, instanceMessage, instanceStatus);
                this.sendServiceCheck(reporter, instance, instanceMessage, scStatus);
            }

        }


        // Iterate over broken" instances to fix them by resetting them
        it = brokenInstances.iterator();
        while (it.hasNext()) {
            Instance instance = it.next();

            // Clearing rates aggregator so we won't compute wrong rates if we can reconnect
            reporter.clearRatesAggregator(instance.getName());

            LOGGER.warn("Instance " + instance + " didn't return any metrics." +
                    "Maybe the server got disconnected ? Trying to reconnect.");

            // Remove the broken instance from the good instance list so jmxfetch won't try to collect metrics from this broken instance during next collection
            instance.cleanUp();
            instances.remove(instance);

            // Resetting the instance
            Instance newInstance = new Instance(instance, appConfig);
            try {
                // Try to reinit the connection and force to renew it
                LOGGER.info("Trying to reconnect to: " + newInstance);
                newInstance.init(true);
                // If we are here, the connection succeeded, the instance is fixed. It can be readded to the good instances list
                instances.add(newInstance);
                it.remove();
            } catch(Exception e) {
                String warning = null;

                if(e instanceof IOException ) {
                    warning = CANNOT_CONNECT_TO_INSTANCE + instance + ". Is a JMX Server running at this address?";
                    LOGGER.warn(warning);
                } else if (e instanceof SecurityException) {
                    warning = CANNOT_CONNECT_TO_INSTANCE + instance + " because of bad credentials. Please check your credentials";
                    LOGGER.warn(warning);
                } else if (e instanceof FailedLoginException) {
                    warning = CANNOT_CONNECT_TO_INSTANCE + instance + " because of bad credentials. Please check your credentials";
                    LOGGER.warn(warning);
                } else {
                    warning = CANNOT_CONNECT_TO_INSTANCE + instance + " for an unknown reason." + e.getMessage();
                    LOGGER.fatal(warning, e);
                }

                this.reportStatus(appConfig, reporter, instance, 0, warning, Status.STATUS_ERROR);
                this.sendServiceCheck(reporter, instance, warning, Status.STATUS_ERROR);
            }
        }

        try {
            appConfig.getStatus().flush();
        } catch (Exception e) {
            LOGGER.error("Unable to flush stats.", e);
        }
    }

    public boolean addConfig(String name, YamlParser config) {
        // named groups not supported with Java6: "(?<check>.{1,30})_(?<version>\\d{0,30})"
        Pattern pattern = Pattern.compile(AUTO_DISCOVERY_PREFIX+"(.{1,30})_(\\d{0,30})");

        Matcher matcher = pattern.matcher(name);
        if (!matcher.find()) {
            // bad name.
            return false;
        }

        // Java 6 doesn't allow name matching - group 1 is "check"
        String check = matcher.group(1);
        if (this.configs.containsKey(check)) {
            // there was already a file config for the check.
            return false;
        }

        this.adConfigs.put(name, config);
        this.setReinit(true);

        return true;
    }

    private ConcurrentHashMap<String, YamlParser> getConfigs(AppConfig config) {
        ConcurrentHashMap<String, YamlParser> configs = new ConcurrentHashMap<String, YamlParser>();
        YamlParser fileConfig;

        List<String> fileList = config.getYamlFileList();
        if (fileList == null) {
            return configs;
        }

        for (String fileName : fileList) {
            File f = new File(config.getConfdDirectory(), fileName);
            String name = f.getName().replace(".yaml", "");
            FileInputStream yamlInputStream = null;
            String yamlPath = f.getAbsolutePath();
            try {
                LOGGER.info("Reading " + yamlPath);
                yamlInputStream = new FileInputStream(yamlPath);
                fileConfig = new YamlParser(yamlInputStream);
                configs.put(name, fileConfig);
            } catch (FileNotFoundException e) {
                LOGGER.warn("Cannot find " + yamlPath);
            } catch (Exception e) {
                LOGGER.warn("Cannot parse yaml file " + yamlPath, e);
            } finally {
                if (yamlInputStream != null) {
                    try {
                        yamlInputStream.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }

        LOGGER.info("Found " + configs.size() + " config files");
        return configs;
    }

    private void reportStatus(AppConfig appConfig, Reporter reporter, Instance instance,
                              int metricCount, String message, String status) {
        String checkName = instance.getCheckName();

        appConfig.getStatus().addInstanceStats(checkName, instance.getName(),
                                               metricCount, reporter.getServiceCheckCount(checkName),
                                               message, status);
    }

    private void sendServiceCheck(Reporter reporter, Instance instance, String message,
                                  String status) {
        String checkName = instance.getCheckName();

        reporter.sendServiceCheck(checkName, status, message, instance.getServiceCheckTags());
        reporter.resetServiceCheckCount(checkName);
    }

    public void init(boolean forceNewConnection) {
        clearInstances(instances);
        clearInstances(brokenInstances);


        Reporter reporter = appConfig.getReporter();


        Iterator<Entry<String, YamlParser>> it = configs.entrySet().iterator();
        // SD config cache doesn't remove configs - it just overwrites.
        Iterator<Entry<String, YamlParser>> itSD = adConfigs.entrySet().iterator();
        while (it.hasNext() || itSD.hasNext()) {
            Map.Entry<String, YamlParser> entry;
            boolean sdIterator = false;
            if (it.hasNext()) {
                entry = it.next();
            } else {
                entry = itSD.next();
                sdIterator = true;
            }

            String name = entry.getKey();
            YamlParser yamlConfig = entry.getValue();
            if(!sdIterator) {
                it.remove();
            }

            ArrayList<LinkedHashMap<String, Object>> configInstances = ((ArrayList<LinkedHashMap<String, Object>>) yamlConfig.getYamlInstances());
            if (configInstances == null || configInstances.size() == 0) {
                String warning = "No instance found in :" + name;
                LOGGER.warn(warning);
                appConfig.getStatus().addInitFailedCheck(name, warning, Status.STATUS_ERROR);
                continue;
            }

            for (LinkedHashMap<String, Object> configInstance : configInstances) {
                Instance instance;
                //Create a new Instance object
                try {
                    instance = new Instance(configInstance, (LinkedHashMap<String, Object>) yamlConfig.getInitConfig(),
                            name, appConfig);
                } catch (Exception e) {
                    String warning = "Unable to create instance. Please check your yaml file";
                    appConfig.getStatus().addInitFailedCheck(name, warning, Status.STATUS_ERROR);
                    LOGGER.error(warning, e);
                    continue;
                }
                try {
                    //  initiate the JMX Connection
                    instance.init(forceNewConnection);
                    instances.add(instance);
                } catch (IOException e) {
                    instance.cleanUp();
                    brokenInstances.add(instance);
                    String warning = CANNOT_CONNECT_TO_INSTANCE + instance + ". " + e.getMessage();
                    this.reportStatus(appConfig, reporter, instance, 0, warning, Status.STATUS_ERROR);
                    this.sendServiceCheck(reporter, instance, warning, Status.STATUS_ERROR);
                    LOGGER.error(warning, e);
                } catch (Exception e) {
                    instance.cleanUp();
                    brokenInstances.add(instance);
                    String warning = "Unexpected exception while initiating instance " + instance + " : " + e.getMessage();
                    this.reportStatus(appConfig, reporter, instance, 0, warning, Status.STATUS_ERROR);
                    this.sendServiceCheck(reporter, instance, warning, Status.STATUS_ERROR);
                    LOGGER.error(warning, e);
                }
            }
        }
    }
}
