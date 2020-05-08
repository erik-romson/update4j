package org.update4j.service;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.update4j.Configuration;
import org.update4j.FileMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.Assert.*;

public class TestDefaultBootstrapProcessRun {

    public static final String TEST_APPLICATION_MODULE_NAME = "test-classes-to-launch";
    public static final String TEST_APPLICATION_JAR_NAME_1 = "test-classes-to-launch-1.jar";
    public static final String BASE_URI = "http://localhost:8888";
    private Configuration.Builder configurationBuilder;

    private List<Throwable> uncaughtExceptions = Lists.newArrayList();

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(options().port(8888));
    private Path jarToDownloadPath;
    private String update4jBasePath;

    @Before
    public void before()  {
        Path moduleBaseDir = TestUtil.getModuleBaseDir(getClass());
        update4jBasePath = moduleBaseDir.toAbsolutePath()
                                               .toString() + "/target/blaj";
        //clean local basepath to trigger update
        TestUtil.deleteAll(update4jBasePath);

        //path to jar in test-classes-to-launch
        jarToDownloadPath = Paths.get(moduleBaseDir.toAbsolutePath()
                                                   .toString(),
                                      "../" + TEST_APPLICATION_MODULE_NAME + "/target",
                                      TEST_APPLICATION_JAR_NAME_1);


        configurationBuilder = Configuration
                .builder()
                .baseUri(BASE_URI)
                .basePath(update4jBasePath)
                .files(Stream.of(FileMetadata
                                         .readFrom(Paths.get("../" + TEST_APPLICATION_MODULE_NAME + "/target",
                                                             TEST_APPLICATION_JAR_NAME_1))
                                         .uri("jars/" + TEST_APPLICATION_JAR_NAME_1)
                                         .ignoreBootConflict(true)
                                         .classpath()))
        ;

        //catch all uncaught exceptions
        uncaughtExceptions.clear();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                uncaughtExceptions.add(e);
            }
        });
    }


    @Test
    public void testProcessOk() throws Throwable {
        stubFor(get(urlEqualTo("/jars/" + TEST_APPLICATION_JAR_NAME_1))
                        .willReturn(aResponse()
                                            .withBody(Files.readAllBytes(jarToDownloadPath))));

        String javaHome=System.getProperty("java.home");

        String testClass = "a.b.c.TestMain";
        Configuration configuration = configurationBuilder
                .property("default.launcher.argument.1",javaHome+ "/bin/java")
                .property("default.launcher.argument.2", "-cp")
                .property("default.launcher.argument.3", update4jBasePath+"/jars/" + TEST_APPLICATION_JAR_NAME_1)
                .property("default.launcher.argument.4", testClass)
                .build();

        //stub config
        stubFor(get(urlEqualTo("/update4j.xml"))
                        .willReturn(aResponse()
                                            .withBody(configuration.toString())));

        System.getProperties()
              .setProperty(testClass, "false");
        (new DefaultBootstrap()).main(Lists.newArrayList("--remote",
                                                         BASE_URI + "/update4j.xml"));
        assertEquals(0, uncaughtExceptions.size());
    }

    @Test
    public void testProcessFailed() throws Throwable {
        stubFor(get(urlEqualTo("/jars/" + TEST_APPLICATION_JAR_NAME_1))
                        .willReturn(aResponse()
                                            .withBody(Files.readAllBytes(jarToDownloadPath))));

        String javaHome=System.getProperty("java.home");

        String testClass = "a.b.c.TestMain";
        Configuration configuration = configurationBuilder
                .property("default.launcher.argument.1",javaHome+ "/bin/java")
                .property("default.launcher.argument.2", "-cp "+update4jBasePath+"/jars/" + TEST_APPLICATION_JAR_NAME_1)
                .property("default.launcher.argument.3", testClass)
                .build();

        //stub config
        stubFor(get(urlEqualTo("/update4j.xml"))
                        .willReturn(aResponse()
                                            .withBody(configuration.toString())));

        System.getProperties()
              .setProperty(testClass, "false");
        (new DefaultBootstrap()).main(Lists.newArrayList("--remote",
                                                         BASE_URI + "/update4j.xml"));
        assertEquals(1, uncaughtExceptions.size());
    }


    @Test
    public void testFailedBootstrap() throws Throwable {
        stubFor(get(urlEqualTo("/jars/" + TEST_APPLICATION_JAR_NAME_1))
                        .willReturn(aResponse()
                                            .withBody(Files.readAllBytes(jarToDownloadPath))));

        String testClass = "a.b.c.TestMain";
        Configuration configuration = configurationBuilder
                .property("default.launcher.argument.1","ugga")
                .property("default.launcher.argument.2", "-cp")
                .property("default.launcher.argument.3", update4jBasePath+"/jars/" + TEST_APPLICATION_JAR_NAME_1)
                .property("default.launcher.argument.4", testClass)
                .build();

        //stub config
        stubFor(get(urlEqualTo("/update4j.xml"))
                        .willReturn(aResponse()
                                            .withBody(configuration.toString())));

        System.getProperties()
              .setProperty(testClass, "false");
        (new DefaultBootstrap()).main(Lists.newArrayList("--remote",
                                                         BASE_URI + "/update4j.xml"));
        assertEquals(1, uncaughtExceptions.size());
    }


}
