/*
 * Copyright 2018 Mordechai Meisels
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * 		http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.update4j.service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.update4j.Bootstrap;
import org.update4j.Configuration;
import org.update4j.SingleInstanceManager;
import org.update4j.Update;
import org.update4j.exc.ConnectionException;
import org.update4j.exc.ExceptionUtils;
import org.update4j.exc.InvalidXmlException;
import org.update4j.inject.InjectSource;
import org.update4j.util.ArgUtils;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public class DefaultBootstrap implements Delegate {

    private String remote;
    private String local;
    private String cert;

    private boolean syncLocal;
    private boolean launchFirst;
    private boolean stopOnUpdateError;
    private boolean singleInstance;

    private PublicKey pk = null;

    final private PrintStream out;

    @InjectSource(target = "args")
    private List<String> businessArgs;

    @Override
    public long version() {
        return Long.MIN_VALUE;
    }

    public DefaultBootstrap() {
        this.out=null;
    }

    public DefaultBootstrap(PrintStream out) {
        this.out = out;
    }

    @Override
    public void main(List<String> args) throws Throwable {
        if (args.isEmpty()) {
            welcome();
            return;
        }

        parseArgs(ArgUtils.beforeSeparator(args));

        if (remote == null && local == null) {
            throw new IllegalArgumentException("One of --remote or --local must be supplied.");
        }

        if (launchFirst && local == null) {
            throw new IllegalArgumentException("--launchFirst requires a local configuration.");
        }

        if (syncLocal && remote == null) {
            throw new IllegalArgumentException("--syncLocal requires a remote configuration.");
        }

        if (syncLocal && local == null) {
            throw new IllegalArgumentException("--syncLocal requires a local configuration.");
        }

        if (singleInstance) {
            SingleInstanceManager.execute();
        }

        if (cert != null) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            try (InputStream in = Files.newInputStream(Paths.get(cert))) {
                pk = cf.generateCertificate(in).getPublicKey();
            }
        }

        businessArgs = ArgUtils.afterSeparator(args);

        if (launchFirst) {
			log("launching application...");
            launchFirst();
        } else {
			log("will update application...");
            updateFirst();
        }
    }

    protected void parseArgs(List<String> bootArgs) {

        Map<String, String> parsed = ArgUtils.parseArgs(bootArgs);
        for (Map.Entry<String, String> e : parsed.entrySet()) {
            String arg = e.getKey();

            if ("syncLocal".equals(arg)) {
                ArgUtils.validateNoValue(e);
                syncLocal = true;
            } else if ("launchFirst".equals(arg)) {
                ArgUtils.validateNoValue(e);
                launchFirst = true;
            } else if ("stopOnUpdateError".equals(arg)) {
                ArgUtils.validateNoValue(e);
                stopOnUpdateError = true;
            } else if ("singleInstance".equals(arg)) {
                ArgUtils.validateNoValue(e);
                singleInstance = true;
            } else if ("remote".equals(arg)) {
                ArgUtils.validateHasValue(e);
                remote = e.getValue();
            } else if ("local".equals(arg)) {
                ArgUtils.validateHasValue(e);
                local = e.getValue();
            } else if ("cert".equals(arg)) {
                ArgUtils.validateHasValue(e);
                cert = e.getValue();
            } else if ("delegate".equals(arg)) {
                throw new IllegalArgumentException("--delegate must be passed as first argument.");
            } else {
                throw new IllegalArgumentException(
                                "Unknown option \"" + arg + "\". Separate business app arguments with '--'.");
            }
        }
    }

    protected void updateFirst() throws Throwable {
        Configuration remoteConfig = null;
        Configuration localConfig = null;

        if (remote != null) {
			log("getting remote config ...");
            remoteConfig = getRemoteConfig();
        }

        if (local != null) {
			log("getting local config ...");
            localConfig = getLocalConfig(remoteConfig != null && syncLocal);
        }

        if (remoteConfig == null && localConfig == null) {
            return;
        }

        Configuration config = remoteConfig != null ? remoteConfig : localConfig;
        boolean failedRemoteUpdate = false;

        if (config.requiresUpdate()) {
			log("requires update ...");
            boolean success = config.update(pk);
            if (config == remoteConfig){
                failedRemoteUpdate = !success;
            }
			if (!success){
				log("remote update failed ...");
			}else {
				log("remote update succeeded ...");
			}
            if (!success && stopOnUpdateError) {
                return;
            }
        }

        if (syncLocal && !failedRemoteUpdate && remoteConfig != null && !remoteConfig.equals(localConfig)) {
            syncLocal(remoteConfig);

            if (localConfig != null) {
                try {
                    remoteConfig.deleteOldFiles(localConfig);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

		log("launching application from "+config.getFiles().stream().map(f->f.getPath().toString()).collect(Collectors.joining(", "))+"...");
		log("using bootstrapping from version %s",getVersion());
		log("starting with properties: " + config.getProperties().stream().map(p-> "["+p.getKey()+","+p.getValue()+"]").collect(
				Collectors.joining(";\n","{","}")));
        config.launch(this);

    }

    protected String getVersion(){
        return "1.4.5";
    }

    protected void launchFirst() throws Throwable {
        Path tempDir = Paths.get("update");
        // used for deleting old files
        Path old = tempDir.resolve(local + ".old");

        Configuration localConfig = getLocalConfig(false);

        if (Update.containsUpdate(tempDir)) {
            Configuration oldConfig = null;
            if (Files.exists(old)) {
                try {
                    try (Reader in = Files.newBufferedReader(old)) {
                        oldConfig = Configuration.read(in);
                    }
                    Files.deleteIfExists(old);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            boolean finalized = Update.finalizeUpdate(tempDir);
            if (finalized && oldConfig != null && localConfig != null) {
                localConfig.deleteOldFiles(oldConfig);
            }
        }

        boolean localNotReady = localConfig == null || localConfig.requiresUpdate();

        if (!localNotReady) {
            Configuration finalConfig = localConfig;
            Thread localApp = new Thread(() -> finalConfig.launch(this));
            localApp.run();
        }

        Configuration remoteConfig = null;
        if (remote != null) {
            remoteConfig = getRemoteConfig();
        }

        boolean failedRemoteUpdate = false;

        if (localNotReady) {
            Configuration config = remoteConfig != null ? remoteConfig : localConfig;

            if (config != null) {
                boolean success = !config.update(pk);
                if (config == remoteConfig){
                    failedRemoteUpdate = !success;
                }

                if (!success && stopOnUpdateError) {
                    return;
                }

				log("launching application ...");
                config.launch(this);
            }
        } else if (remoteConfig != null) {
            if (remoteConfig.requiresUpdate()) {
                failedRemoteUpdate = !remoteConfig.updateTemp(tempDir, pk);
            }
        }

        if (!failedRemoteUpdate && remoteConfig != null && !remoteConfig.equals(localConfig)) {
            syncLocal(remoteConfig);

            if (localNotReady && localConfig != null) {
                remoteConfig.deleteOldFiles(localConfig);
            } else if (Update.containsUpdate(tempDir)) {
                try (Writer out = Files.newBufferedWriter(old)) {
                    localConfig.write(out);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    protected Reader openConnection(URL url) throws IOException {

        URLConnection connection = url.openConnection();

        // Some downloads may fail with HTTP/403, this may solve it
        connection.addRequestProperty("User-Agent", "Mozilla/5.0");
        // Set a connection timeout of 10 seconds
        connection.setConnectTimeout(10 * 1000);
        // Set a read timeout of 10 seconds
        connection.setReadTimeout(10 * 1000);

        return new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8);
    }

    protected Configuration getLocalConfig(boolean ignoreFileNotFound) {
		log("reading local config from "+local.toString());
        try (Reader in = Files.newBufferedReader(Paths.get(local))) {
            if (pk == null) {
                return Configuration.read(in);
            } else {
                return Configuration.read(in, pk);

            }
        } catch (NoSuchFileException e) {
            if (!ignoreFileNotFound) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            // All exceptions just returns null, never fail
            e.printStackTrace();
        }

        return null;
    }

    protected Configuration getRemoteConfig() {
        try (Reader in = openConnection(new URL(remote))) {
            if (pk == null) {
                return Configuration.read(in);
            } else {
                return Configuration.read(in, pk);

            }
        } catch (Exception e) {
            List<Throwable> throwableList = ExceptionUtils.getThrowableList(e);
            if (ExceptionUtils.contains(throwableList, SAXException.class)){
                throw new InvalidXmlException(remote, e);
            }else if (ExceptionUtils.contains(throwableList, IOException.class)) {
                throw new ConnectionException(e);
            } else if (e instanceof RuntimeException){
                throw (RuntimeException)e;
            }else {
                throw new RuntimeException(e);
            }
        }
    }

    protected void syncLocal(Configuration remoteConfig) {
        Path localPath = Paths.get(local);
		log("syncing local with remote config "+localPath.getParent().toString());
        try {
            if (localPath.getParent() != null)
                Files.createDirectories(localPath.getParent());

            try (Writer out = Files.newBufferedWriter(localPath)) {
                remoteConfig.write(out);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // @formatter:off
    protected static void welcome() {

        System.out.println(getLogo() + "\tWelcome to update4j, where you create your own auto-update lifecycle.\n\n"
                + "\tYou started its default bootstrap -- the built-in lifecycle -- which does\n"
                + "\tthe update and launch logic for you without complex setup. All you need is to\n"
                + "\tspecify some settings via command line arguments.\n\n"
                + "\tBefore you start, you first need to create a \"configuration\" file that contains\n"
                + "\tall details required to run. You can create one by using Configuration.builder()\n"
                + "\tBuilder API. For automation, you might use a build-tool plugin that executes\n"
                + "\tyour configuration generation on each build (Maven's exec-maven-plugin for one).\n\n"
                + "\tFor more details how to create a configuration please refer to the Javadoc:\n"
                + "\thttp://docs.update4j.org/javadoc/update4j/org.update4j/org/update4j/Configuration.html\n\n"
                + "\tWhile the default bootstrap works perfectly as an ad-hoc out-of-the-box setup, you might\n"
                + "\tfurther customize the update and launch lifecycle to the last detail by\n"
                + "\timplementing a custom bootstrap and update/launch your business application\n"
                + "\tusing the Configuration.update() and Configuration.launch() methods.\n\n"
                + "\tFor more details about implementing the bootstrap, please refer to the Github wiki:\n"
                + "\thttps://github.com/update4j/update4j/wiki/Documentation#lifecycle\n\n");

        usage();
    }

    protected static String getLogo() {

        return

        "\n"
                + "\t                 _       _          ___ _ \n"
                + "\t                | |     | |        /   (_)\n"
                + "\t _   _ _ __   __| | __ _| |_ ___  / /| |_ \n"
                + "\t| | | | '_ \\ / _` |/ _` | __/ _ \\/ /_| | |\n"
                + "\t| |_| | |_) | (_| | (_| | ||  __/\\___  | |\n"
                + "\t \\__,_| .__/ \\__,_|\\__,_|\\__\\___|    |_/ |\n"
                + "\t      | |                             _/ |\n"
                + "\t      |_|                            |__/ \n\n\n"

        ;
    }

    private static void usage() {

        String output = "To start in modulepath:\n\n"
                + "\tjava -p update4j-$version$.jar -m org.update4j [commands...] [-- business-args...]\n"
                + "\tjava -p . -m org.update4j [commands...] [-- business-args...]\n\n\n"
                + "\tWhen starting in the modulepath, be aware that as a fundamental restriction\n"
                + "\tof the modulepath only the boot (JVM native) modulepath can resolve *system*\n"
                + "\tmodules into the module graph. Therefore when the business application depends\n"
                + "\ton a system module that is not required by the bootstrap application\n"
                + "\tyou should still add a 'requires' directive in the bootstrap module in order\n"
                + "\tto make it end up in the module graph.\n\n"
                + "\tAlternatively, use the '--add-modules' flag to manually resolve those required\n"
                + "\tsystem modules.\n"
                + "\tIf you use the default bootstrap (and you don't have control to add more 'requires')\n"
                + "\tyou must use this solution (or, of course, start in the classpath).\n\n\n"
                + "To start in classpath:\n\n"
                + "\tjava -jar update4j-$version$.jar [commands...] [-- business-args...]\n"
                + "\tjava -cp update4j-$version$.jar org.update4j.Bootstrap [commands...] [-- business-args...]\n"
                + "\tjava -cp * org.update4j.Bootstrap [commands...] [-- business-args...]\n\n\n"
                + "\tWhen starting in the classpath you can still leverage the full power of the Module\n"
                + "\tSystem but only for the business application. If a file is marked with the \"modulepath\"\n"
                + "\tattribute, the Module System will enforce all modularity rules for that individual module.\n\n"
                + "\tUsing this combination of paths is a very simple way to circumvent the system module\n"
                + "\trestriction explained in the previous section, i.e. it will automatically include all\n"
                + "\tsystem modules into the runtime.\n\n\n"
                + "Available commands:\n\n"
                + "\t--remote [url] - The remote (or if using file:/// scheme - local) location of the\n"
                + "\t\tconfiguration file. If it fails to download or command is missing, it will\n"
                + "\t\tfall back to local.\n\n"
                + "\t--local [path] - The path of a local configuration to use if the remote failed to download\n"
                + "\t\tor was not passed. If both remote and local are missing, startup fails.\n\n"
                + "\t--syncLocal - Sync the local configuration with the remote if it downloaded, loaded and\n"
                + "\t\tupdated files successfully. Useful to still allow launching without Internet connection.\n"
                + "\t\tDefault will not sync unless --launchFirst was specified.\n\n"
                + "\t--cert [path] - A path to an X.509 certificate file to use to verify signatures. If missing,\n"
                + "\t\tno signature verification will be performed.\n\n"
                + "\t--launchFirst - If specified, it will first launch the local application then silently\n"
                + "\t\tdownload the update; the update will be available only on next restart. It will still\n"
                + "\t\tdownload the remote and update first if the local config requires an update\n"
                + "\t\t(e.g. files were deleted). Must have a local configuration.\n"
                + "\t\tIf not specified it will update before launch and hang the application until done.\n\n"
                + "\t--stopOnUpdateError - Will stop the launch if an error occurred while downloading an update.\n"
                + "\t\tThis does not include if remote failed to download and it used local as a fallback.\n"
                + "\t\tIf --launchFirst was used, this only applies if the local config requires an update\n"
                + "\t\tand failed.\n\n"
                + "\t--singleInstance - Run the application as a single instance. Any subsequent attempts\n"
                + "\t\tto run will just exit. You can better control this feature by directly using the\n"
                + "\t\tSingleInstanceManager class.\n\n\n"
                + "To pass arguments to the business application, separate them with '--' (w/o quotes).";
        
                System.err.println(output.replace("$version$", Bootstrap.VERSION));
    }

    protected void log(String str){
        if (out!=null) {
            out.println(str);
        }
    }

    protected void log(String str,String... args){
        if (out!=null) {
            out.println(String.format(str, args));
        }
    }


}
