package com.xscsiem.hsiem_platform.onboarding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Process-backed Logstash deployer. */
@Component
@ConditionalOnProperty(name = "app.operations.process-adapters", havingValue = "enabled")
public class ProcessLogstashDeployer implements LogstashDeployer {

    private static final long TIMEOUT_SECONDS = 120;
    private static final String CONTAINER_PIPELINE_ROOT = "/usr/share/logstash/pipeline/";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    /* Inputs are positional arguments; never interpolate them into these scripts. */
    private static final String SYNC_LOGSTASH_SCRIPT =
            "set -e\n" + "rsync -a --delete -- \"$1/infra/logstash/\" \"$2/logstash/\"\n";
    private static final String SYNC_COMPOSE_SCRIPT =
            "set -e\n" + "cp -- \"$1/infra/$3\" \"$2/$3\"\n";
    private static final String RESTART_SCRIPT =
            "set -e\n"
                    + "cd -- \"$1\"\n"
                    + "COMPOSE_PROJECT_NAME=\"${COMPOSE_PROJECT_NAME:-infra}\" "
                    + "docker compose -f \"$2\" up -d logstash\n";

    private final String wslRepoPath;
    private final String deployDir;
    private final String containerName;
    private final String composeName;
    private final CommandExecutor commandExecutor;

    public ProcessLogstashDeployer(
            @Value("${app.logstash.wsl-repo-path:/mnt/d/Project/SIEM}") String wslRepoPath,
            @Value("${app.logstash.deploy-dir:~/projects/mini-siem}") String deployDir,
            @Value("${app.logstash.container-name:siem-logstash}") String containerName,
            @Value("${app.logstash.compose-name:docker-compose.yml}") String composeName) {
        this(
                wslRepoPath,
                deployDir,
                containerName,
                composeName,
                ProcessLogstashDeployer::executeProcess);
    }

    /** Constructor seam for tests and process-capture integrations. */
    ProcessLogstashDeployer(
            String wslRepoPath,
            String deployDir,
            String containerName,
            String composeName,
            CommandExecutor commandExecutor) {
        this.wslRepoPath = normalizeConfiguredPath(wslRepoPath, "wslRepoPath", false);
        this.deployDir = normalizeConfiguredPath(deployDir, "deployDir", true);
        this.containerName = validateName(containerName, "containerName");
        this.composeName = validateName(composeName, "composeName");
        if (commandExecutor == null) {
            throw new IllegalArgumentException("commandExecutor must not be null");
        }
        this.commandExecutor = commandExecutor;
    }

    @Override
    public void syncLogstash() {
        if (!exitOk("wsl", "bash", "-c", SYNC_LOGSTASH_SCRIPT, "--", wslRepoPath, deployDir)) {
            throw new IllegalStateException("同步 logstash 到 WSL 失败");
        }
        if (!exitOk(
                "wsl",
                "bash",
                "-c",
                SYNC_COMPOSE_SCRIPT,
                "--",
                wslRepoPath,
                deployDir,
                composeName)) {
            throw new IllegalStateException("同步 docker-compose.yml 到 WSL 失败");
        }
    }

    @Override
    public boolean validateConfig(String containerConfigPath) {
        String normalizedPath = normalizeContainerConfigPath(containerConfigPath);
        if (normalizedPath == null) {
            return false;
        }
        String tmpData =
                "/tmp/ls-validate-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return exitOk(
                "docker",
                "exec",
                containerName,
                "logstash",
                "--config.test_and_exit",
                "-f",
                normalizedPath,
                "--path.data=" + tmpData);
    }

    @Override
    public void restartLogstash() {
        String composeFile = deployDir + "/" + composeName;
        if (!exitOk("wsl", "bash", "-c", RESTART_SCRIPT, "--", deployDir, composeName)) {
            throw new IllegalStateException(
                    "重建 Logstash 容器失败: "
                            + composeFile
                            + "(配置已同步，可手动执行 docker compose up -d logstash)");
        }
    }

    @Override
    public void reloadLogstash() {
        if (!exitOk("docker", "exec", containerName, "kill", "-HUP", "1")) {
            throw new IllegalStateException("热加载 Logstash pipeline 失败");
        }
    }

    private boolean exitOk(String... cmd) {
        return commandExecutor.execute(List.copyOf(Arrays.asList(cmd)));
    }

    private static String normalizeConfiguredPath(String raw, String field, boolean allowHome) {
        String value = checkedText(raw, field).trim().replace('\\', '/');
        boolean homePath = value.equals("~") || value.startsWith("~/");
        if ((!allowHome && homePath) || (!homePath && !value.startsWith("/"))) {
            throw new IllegalArgumentException(field + " must be an absolute WSL path");
        }
        if (homePath && value.startsWith("~//")) {
            value = "~/" + value.substring(3);
        }
        String prefix = homePath ? "~/" : "/";
        String body = homePath ? value.substring(value.equals("~") ? 1 : 2) : value.substring(1);
        List<String> segments = normalizedSegments(body, field);
        if (segments.isEmpty()) {
            if (homePath) {
                return "~";
            }
            throw new IllegalArgumentException(field + " must contain a directory");
        }
        return prefix + String.join("/", segments);
    }

    private static List<String> normalizedSegments(String body, String field) {
        List<String> result = new ArrayList<>();
        for (String segment : body.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(field + " must not contain traversal segments");
            }
            if (segment.indexOf('\u0000') >= 0
                    || segment.indexOf('\r') >= 0
                    || segment.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(field + " contains an unsafe control character");
            }
            result.add(segment);
        }
        return result;
    }

    private static String validateName(String raw, String field) {
        String value = checkedText(raw, field).trim();
        if (!NAME.matcher(value).matches() || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(field + " must be a safe basename");
        }
        return value;
    }

    private static String normalizeContainerConfigPath(String raw) {
        String value;
        try {
            value = checkedText(raw, "containerConfigPath").trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!value.startsWith(CONTAINER_PIPELINE_ROOT) || value.indexOf('\\') >= 0) {
            return null;
        }
        List<String> segments;
        try {
            segments =
                    normalizedSegments(
                            value.substring(CONTAINER_PIPELINE_ROOT.length()),
                            "containerConfigPath");
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (segments.isEmpty()) {
            return null;
        }
        if (segments.stream().anyMatch(segment -> !PATH_SEGMENT.matcher(segment).matches())) {
            return null;
        }
        String basename = segments.get(segments.size() - 1);
        if (!basename.endsWith(".conf") || !PATH_SEGMENT.matcher(basename).matches()) {
            return null;
        }
        return CONTAINER_PIPELINE_ROOT + String.join("/", segments);
    }

    private static String checkedText(String raw, String field) {
        if (raw == null
                || raw.isBlank()
                || raw.indexOf('\u0000') >= 0
                || raw.indexOf('\r') >= 0
                || raw.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " must not be blank or contain NUL/CR/LF");
        }
        return raw;
    }

    private static boolean executeProcess(List<String> command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            CompletableFuture<String> output =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return new String(
                                            p.getInputStream().readAllBytes(),
                                            StandardCharsets.UTF_8);
                                } catch (IOException e) {
                                    return "[读取外部命令输出失败] " + e.getMessage();
                                }
                            });
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                output.cancel(true);
                return false;
            }
            try {
                System.out.print(output.get(5, TimeUnit.SECONDS));
            } catch (ExecutionException | TimeoutException e) {
                System.out.print(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    interface CommandExecutor {
        boolean execute(List<String> command);
    }
}
