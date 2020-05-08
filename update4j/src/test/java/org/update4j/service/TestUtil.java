package org.update4j.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestUtil {
    static Path getModuleBaseDir(Class aClass) {
        Path path = Paths.get(aClass.getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .getPath());
        while (!path.resolve("pom.xml")
                    .toFile()
                    .exists()) {
            path = path.getParent();
        }
        return path;
    }

    static void deleteAll(String update4jBasePath) {
        try {
            Files.walk(Paths.get(update4jBasePath))
                 .filter(Files::isRegularFile)
                 .map(Path::toFile)
                 .forEach(File::delete);
        } catch (IOException e) {
            //nada
        }
    }
}
