package com.xscsiem.hsiem_platform.detection.runtime.process;

import java.time.Duration;
import java.util.List;

/** Narrow process boundary; callers pass an argument vector, never a shell command string. */
public interface CommandRunner {

    CommandResult run(List<String> arguments, Duration timeout);

    default CommandResult run(Duration timeout, String... arguments) {
        return run(List.of(arguments), timeout);
    }

    record CommandResult(int exitCode, String stdout, String stderr) {
        public CommandResult {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }

        public boolean successful() {
            return exitCode == 0;
        }
    }
}
