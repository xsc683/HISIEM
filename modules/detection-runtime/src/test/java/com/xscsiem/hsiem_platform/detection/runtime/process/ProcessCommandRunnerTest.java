package com.xscsiem.hsiem_platform.detection.runtime.process;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandRunnerTest {

    @Test
    void passesArgumentsWithoutShellAndDrainsBothOutputStreams() {
        CommandRunner.CommandResult result = new ProcessCommandRunner().run(
                List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                        OutputProcess.class.getName(), "value with spaces", "$(not-a-shell-expansion)"),
                Duration.ofSeconds(10));

        assertEquals(0, result.exitCode());
        assertEquals("value with spaces|$(not-a-shell-expansion)", result.stdout());
        assertEquals("stderr", result.stderr());
    }

    @Test
    void timeoutTerminatesChildAndReportsCommandArguments() {
        ProcessCommandRunner.CommandRunnerException failure = assertThrows(
                ProcessCommandRunner.CommandRunnerException.class,
                () -> new ProcessCommandRunner().run(
                        List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                                SleepProcess.class.getName()),
                        Duration.ofMillis(100)));

        assertTrue(failure.getMessage().contains("timed out"));
        assertTrue(failure.arguments().contains(SleepProcess.class.getName()));
    }

    @Test
    void interruptTerminatesChildRestoresInterruptFlagAndReturns() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                new ProcessCommandRunner().run(
                        List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                                SleepProcess.class.getName()),
                        Duration.ofSeconds(30));
            } catch (ProcessCommandRunner.CommandRunnerException expected) {
                interrupted.set(Thread.currentThread().isInterrupted());
            } finally {
                completed.set(true);
            }
        });
        worker.start();
        Thread.sleep(100);
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive());
        assertTrue(completed.get());
        assertTrue(interrupted.get());
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
    }

    public static final class OutputProcess {
        public static void main(String[] args) {
            System.out.print(args[0] + "|" + args[1]);
            System.err.print("stderr");
        }
    }

    public static final class SleepProcess {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30_000);
        }
    }
}
