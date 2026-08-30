package com.xscsiem.hsiem_platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleBoundaryTest {

    private static final Pattern DEPENDENCY = Pattern.compile(
            "<dependency>(.*?)</dependency>", Pattern.DOTALL);

    @Test
    void soarCoreSourceAndPomDoNotContainTransportRuntimeTypes() throws IOException {
        Path root = repositoryRoot();
        Path source = root.resolve("modules/soar-core/src/main/java");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("org.apache.kafka."), file.toString());
                assertFalse(text.contains("org.springframework.boot.health."), file.toString());
                assertFalse(text.contains("java.net.http."), file.toString());
                assertFalse(text.contains("@Scheduled"), file.toString());
            }
        }
        String pom = Files.readString(root.resolve("modules/soar-core/pom.xml"));
        assertFalse(pom.contains("kafka-clients"));
        assertFalse(pom.contains("spring-boot-starter-actuator"));
    }

    @Test
    void controlApiKeepsWorkerRuntimeOutOfProductionDependencies() throws IOException {
        String pom = Files.readString(repositoryRoot().resolve("applications/control-api/pom.xml"));
        Matcher matcher = DEPENDENCY.matcher(pom);
        boolean foundTestOnlyRuntime = false;
        while (matcher.find()) {
            String dependency = matcher.group(1);
            if (dependency.contains("<artifactId>soar-worker-runtime</artifactId>")) {
                assertTrue(dependency.contains("<scope>test</scope>"));
                foundTestOnlyRuntime = true;
            }
        }
        assertTrue(foundTestOnlyRuntime, "control-api should retain worker tests through test scope only");
        assertFalse(pom.contains("<artifactId>soar-worker</artifactId>"));
    }

    @Test
    void migrationsHaveOnePhysicalOwner() throws IOException {
        Path root = repositoryRoot();
        assertTrue(Files.isDirectory(root.resolve("modules/platform-migrations/src/main/resources/db/migration")));
        assertFalse(Files.exists(root.resolve("applications/control-api/src/main/resources/db/migration")));
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("modules/soar-core"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }
}
