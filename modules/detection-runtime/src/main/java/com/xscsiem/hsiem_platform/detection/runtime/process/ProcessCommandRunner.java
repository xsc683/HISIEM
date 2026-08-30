package com.xscsiem.hsiem_platform.detection.runtime.process;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Production command runner using ProcessBuilder argument vectors and bounded stream drains. */
public final class ProcessCommandRunner implements CommandRunner {

    @Override
    public CommandResult run(List<String> arguments, Duration timeout) {
        validate(arguments, timeout);
        Process process;
        try {
            process = new ProcessBuilder(arguments).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new CommandRunnerException("unable to start process", arguments, e);
        }

        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                terminate(process);
                cancel(stdout, stderr);
                throw new CommandRunnerException("command timed out", arguments);
            }
            // The process has exited, but both pipes still need to be joined.  This is bounded so a
            // broken child cannot hold the controller forever after its process has ended.
            return new CommandResult(process.exitValue(), await(stdout, timeout), await(stderr, timeout));
        } catch (InterruptedException e) {
            terminate(process);
            cancel(stdout, stderr);
            Thread.currentThread().interrupt();
            throw new CommandRunnerException("command interrupted", arguments, e);
        } catch (TimeoutException e) {
            terminate(process);
            cancel(stdout, stderr);
            throw new CommandRunnerException("reading command output timed out", arguments, e);
        } catch (ExecutionException e) {
            terminate(process);
            cancel(stdout, stderr);
            throw new CommandRunnerException("reading command output failed", arguments, e.getCause());
        }
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CommandRunnerException("unable to read command output", List.of(), e);
            }
        });
    }

    private static String await(CompletableFuture<String> future, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        long millis = Math.max(1L, timeout.toMillis());
        return future.get(millis, TimeUnit.MILLISECONDS);
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void cancel(CompletableFuture<String> stdout, CompletableFuture<String> stderr) {
        stdout.cancel(true);
        stderr.cancel(true);
    }

    private static void validate(List<String> arguments, Duration timeout) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        if (arguments.isEmpty() || arguments.stream().anyMatch(argument -> argument == null || argument.isBlank())) {
            throw new IllegalArgumentException("command arguments must be non-empty and non-blank");
        }
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static final class CommandRunnerException extends RuntimeException {
        private final List<String> arguments;

        public CommandRunnerException(String message, List<String> arguments) {
            super(message + ": " + String.join(" ", arguments));
            this.arguments = List.copyOf(arguments);
        }

        public CommandRunnerException(String message, List<String> arguments, Throwable cause) {
            super(message + ": " + String.join(" ", arguments), cause);
            this.arguments = List.copyOf(arguments);
        }

        public List<String> arguments() {
            return arguments;
        }
    }
}
