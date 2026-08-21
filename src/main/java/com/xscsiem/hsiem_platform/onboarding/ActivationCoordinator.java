package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 生效协调(Story 01 FR-3):把数据源落成 Logstash per-source pipeline 并同步生效。
 *
 * 链路(失败任一步回滚):生成完整 pipeline → 写 repo infra/logstash/pipeline/log-sources/&lt;id&gt;.conf
 *   + pipelines.yml 追加条目 → rsync 同步 WSL → 容器内 --config.test_and_exit 校验
 *   → 通过则重启 Logstash;校验/同步/重启失败 → 删除 conf + 还原 pipelines.yml → 抛 ActivationFailedException。
 *
 * 外部命令统一走 {@link LogstashDeployer}(可 mock);路径可由 application.properties 覆盖。
 */
@Component
public class ActivationCoordinator {

    private final LogstashConfigGenerator generator;
    private final LogstashDeployer deployer;
    private final String pipelineDir;
    private final String configDir;
    private final String containerPipelineRoot;
    private final String composeFile;

    public ActivationCoordinator(
            LogstashConfigGenerator generator,
            LogstashDeployer deployer,
            @Value("${app.logstash.pipeline-dir:infra/logstash/pipeline}") String pipelineDir,
            @Value("${app.logstash.config-dir:infra/logstash/config}") String configDir,
            @Value("${app.logstash.container-pipeline-root:/usr/share/logstash/pipeline}") String containerPipelineRoot,
            @Value("${app.logstash.compose-file:infra/docker-compose.yml}") String composeFile) {
        this.generator = generator;
        this.deployer = deployer;
        this.pipelineDir = pipelineDir;
        this.configDir = configDir;
        this.containerPipelineRoot = containerPipelineRoot;
        this.composeFile = composeFile;
    }

    /** 生效;失败已回滚文件并抛 {@link ActivationFailedException},由调用方置 failed 状态。 */
    public void activate(LogSource s, ParserTemplate t) {
        Path confFile = Path.of(pipelineDir, "log-sources", s.id + ".conf");
        Path pipelines = Path.of(configDir, "pipelines.yml");
        Path compose = Path.of(composeFile);
        String containerPath = containerPipelineRoot + "/log-sources/" + s.id + ".conf";
        String pipelinesBackup = null;
        String composeBackup = null;
        try {
            String conf = generator.generatePipeline(s, t);
            Files.createDirectories(confFile.getParent());
            Files.createDirectories(pipelines.getParent());

            pipelinesBackup = Files.exists(pipelines) ? Files.readString(pipelines) : "";
            Files.writeString(confFile, conf);

            String entry = "- pipeline.id: " + pipelineId(s) + "\n"
                    + "  path.config: \"" + containerPath + "\"\n"
                    + "  pipeline.ecs_compatibility: v8\n";
            String base = (pipelinesBackup == null || pipelinesBackup.isEmpty())
                    ? "" : (pipelinesBackup.endsWith("\n") ? pipelinesBackup : pipelinesBackup + "\n");
            Files.writeString(pipelines, base + entry);

            // 数据源端口映射到宿主机(路线B):把 "<port>:<port>" 加进 compose 的 logstash ports(幂等)。
            // 同步/校验/重启任一步失败 → 还原 compose(见 rollback)。
            composeBackup = addPortToCompose(compose, s.port);

            deployer.syncLogstash();
            if (!deployer.validateConfig(containerPath)) {
                rollback(confFile, pipelines, pipelinesBackup, compose, composeBackup);
                throw new ActivationFailedException("Logstash 配置校验失败(" + s.id + "),已回滚");
            }
            if (portProtocol(s)) {
                // 新增/移除宿主机端口时必须重建 compose；file pipeline 可直接 HUP 热加载。
                deployer.restartLogstash();
            } else {
                deployer.reloadLogstash();
            }
        } catch (ActivationFailedException e) {
            throw e;
        } catch (IOException e) {
            rollback(confFile, pipelines, pipelinesBackup, compose, composeBackup);
            throw new ActivationFailedException("生效失败(写入/同步): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollback(confFile, pipelines, pipelinesBackup, compose, composeBackup);
            throw new ActivationFailedException("生效失败(同步/重启): " + e.getMessage(), e);
        }
    }

    /** 停用数据源并移除 pipeline、端口映射；任一步失败都恢复原文件。 */
    public void deactivate(LogSource s) {
        Path confFile = Path.of(pipelineDir, "log-sources", s.id + ".conf");
        Path pipelines = Path.of(configDir, "pipelines.yml");
        Path compose = Path.of(composeFile);
        boolean confExisted = false;
        String confBackup = null;
        String pipelinesBackup = null;
        String composeBackup = null;
        try {
            confExisted = Files.exists(confFile);
            if (confExisted) {
                confBackup = Files.readString(confFile);
            }
            pipelinesBackup = Files.exists(pipelines) ? Files.readString(pipelines) : null;
            composeBackup = Files.exists(compose) ? Files.readString(compose) : null;

            Files.deleteIfExists(confFile);
            if (pipelinesBackup != null) {
                Files.writeString(pipelines, removePipelineEntry(pipelinesBackup, s.id));
            }
            if (composeBackup != null && portProtocol(s)) {
                Files.writeString(compose, removePortFromCompose(composeBackup, s.port));
            }
            deployer.syncLogstash();
            if (portProtocol(s)) {
                deployer.restartLogstash();
            } else {
                deployer.reloadLogstash();
            }
        } catch (IOException | RuntimeException e) {
            restoreDeactivation(confFile, confExisted, confBackup, pipelines, pipelinesBackup,
                    compose, composeBackup);
            throw new ActivationFailedException("停用失败(" + s.id + "),已回滚: " + e.getMessage(), e);
        }
    }

    private static String removePipelineEntry(String original, String pipelineId) {
        String[] lines = original.split("\\R", -1);
        List<String> kept = new ArrayList<>();
        boolean removing = false;
        for (String line : lines) {
            if (line.startsWith("- pipeline.id: ")) {
                removing = line.substring("- pipeline.id: ".length()).trim().equals(pipelineId);
            }
            if (!removing) {
                kept.add(line);
            }
        }
        return String.join("\n", kept);
    }

    private static String removePortFromCompose(String original, int port) {
        if (port <= 0) return original;
        String line = "      - \"" + port + ":" + port + "\"";
        return original.replace(line + "\r\n", "").replace(line + "\n", "");
    }

    private static void restoreDeactivation(Path confFile, boolean confExisted, String confBackup,
                                            Path pipelines, String pipelinesBackup,
                                            Path compose, String composeBackup) {
        try {
            if (confExisted) {
                Files.createDirectories(confFile.getParent());
                Files.writeString(confFile, confBackup);
            } else {
                Files.deleteIfExists(confFile);
            }
            if (pipelinesBackup != null) {
                Files.createDirectories(pipelines.getParent());
                Files.writeString(pipelines, pipelinesBackup);
            }
            if (composeBackup != null) {
                Files.writeString(compose, composeBackup);
            }
        } catch (IOException rollback) {
            System.err.println("[ActivationCoordinator] 停用回滚失败: " + rollback.getMessage());
        }
    }

    /**
     * 把数据源端口映射加进 compose 的 logstash ports(幂等:已存在则不动)。
     * 返回修改前的完整内容(供回滚);若未修改返回 null。
     */
    private String addPortToCompose(Path compose, Integer port) throws IOException {
        if (port == null || port <= 0) {
            return null;
        }
        if (!Files.exists(compose)) {
            throw new IllegalStateException("docker-compose.yml 不存在: " + compose);
        }
        String original = Files.readString(compose);
        String mapping = "\"" + port + ":" + port + "\"";
        if (original.contains(mapping)) {
            return null; // 已映射,无需改动
        }
        // 在 logstash 服务的 ports 区第一个端口项后插入(保持缩进与现有 "- " 一致)
        String needle = "      - \"5000:5000\"\n";
        if (!original.contains(needle)) {
            throw new IllegalStateException("compose 找不到 logstash 基准端口 5000,无法插入映射");
        }
        String updated = original.replaceFirst(needle.replace("\\", "\\\\"),
                needle + "      - " + mapping + "\n");
        if (updated.equals(original)) {
            throw new IllegalStateException("compose 端口插入失败: " + compose);
        }
        Files.writeString(compose, updated);
        return original;
    }

    /** 回滚:删除新 conf,还原 pipelines.yml 与 compose 原内容。失败不掩盖原始错误。 */
    private void rollback(Path confFile, Path pipelines, String pipelinesBackup,
                          Path compose, String composeBackup) {
        try {
            Files.deleteIfExists(confFile);
            if (pipelinesBackup != null) {
                if (pipelinesBackup.isEmpty()) {
                    Files.deleteIfExists(pipelines);
                } else {
                    Files.writeString(pipelines, pipelinesBackup);
                }
            }
            if (composeBackup != null) {
                Files.writeString(compose, composeBackup);
            }
        } catch (IOException e) {
            System.err.println("[ActivationCoordinator] 回滚失败: " + e.getMessage());
        }
    }

    private static String pipelineId(LogSource s) {
        return s.id;
    }

    private static boolean portProtocol(LogSource s) {
        return s.port > 0 && ("tcp".equalsIgnoreCase(s.protocol) || "syslog".equalsIgnoreCase(s.protocol));
    }
}
