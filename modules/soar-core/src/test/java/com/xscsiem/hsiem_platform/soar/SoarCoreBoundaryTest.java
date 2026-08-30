package com.xscsiem.hsiem_platform.soar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoarCoreBoundaryTest {

    @Test
    void productionSourceHasNoTransportSpecificImportsOrWorkerSchedule() throws IOException {
        Path source = repositoryRoot().resolve("modules/soar-core/src/main/java");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertFalse(text.contains("org.apache.kafka."), file.toString());
                assertFalse(text.contains("org.springframework.boot.health."), file.toString());
                assertFalse(text.contains("java.net.http."), file.toString());
                assertFalse(text.contains("@Scheduled"), file.toString());
            }
        }
    }

    @Test
    void productionSourceHasNoSecurityOperationImplementationImports() throws IOException {
        Path source = repositoryRoot().resolve("modules/soar-core/src/main/java");
        String[] forbiddenImports = {
                "import com.xscsiem.hsiem_platform.alert.",
                "import com.xscsiem.hsiem_platform.investigation.",
                "import com.xscsiem.hsiem_platform.logsearch.",
                "import com.xscsiem.hsiem_platform.search."
        };
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                for (String forbiddenImport : forbiddenImports) {
                    assertFalse(text.contains(forbiddenImport), file.toString());
                }
            }
        }
    }

    @Test
    void coreDoesNotDependOnSecurityOpsButAdaptersDo() throws IOException {
        Path root = repositoryRoot();
        String corePom = Files.readString(root.resolve("modules/soar-core/pom.xml"));
        String adaptersPom = Files.readString(root.resolve("modules/soar-adapters/pom.xml"));

        assertFalse(corePom.contains("<artifactId>security-ops</artifactId>"));
        assertTrue(adaptersPom.contains("<artifactId>security-ops</artifactId>"));
    }

    @Test
    void coreCompileClasspathDoesNotExposeKafkaOrActuatorHealth() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.apache.kafka.clients.consumer.ConsumerRecord"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.boot.health.contributor.HealthIndicator"));
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
