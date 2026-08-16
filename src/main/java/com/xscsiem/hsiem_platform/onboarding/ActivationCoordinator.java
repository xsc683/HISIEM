package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    public ActivationCoordinator(
            LogstashConfigGenerator generator,
            LogstashDeployer deployer,
            @Value("${app.logstash.pipeline-dir:infra/logstash/pipeline}") String pipelineDir,
            @Value("${app.logstash.config-dir:infra/logstash/config}") String configDir,
            @Value("${app.logstash.container-pipeline-root:/usr/share/logstash/pipeline}") String containerPipelineRoot) {
        this.generator = generator;
        this.deployer = deployer;
        this.pipelineDir = pipelineDir;
        this.configDir = configDir;
        this.containerPipelineRoot = containerPipelineRoot;
    }

    /** 生效;失败已回滚文件并抛 {@link ActivationFailedException},由调用方置 failed 状态。 */
    public void activate(LogSource s, ParserTemplate t) {
        Path confFile = Path.of(pipelineDir, "log-sources", s.id + ".conf");
        Path pipelines = Path.of(configDir, "pipelines.yml");
        String containerPath = containerPipelineRoot + "/log-sources/" + s.id + ".conf";
        String backup = null;
        try {
            String conf = generator.generatePipeline(s, t);
            Files.createDirectories(confFile.getParent());
            Files.createDirectories(pipelines.getParent());
            backup = Files.exists(pipelines) ? Files.readString(pipelines) : "";

            Files.writeString(confFile, conf);

            String entry = "- pipeline.id: " + pipelineId(s) + "\n"
                    + "  path.config: \"" + containerPath + "\"\n"
                    + "  pipeline.ecs_compatibility: v8\n";
            String base = (backup == null || backup.isEmpty()) ? "" : (backup.endsWith("\n") ? backup : backup + "\n");
            Files.writeString(pipelines, base + entry);

            deployer.syncLogstash();
            if (!deployer.validateConfig(containerPath)) {
                rollback(confFile, pipelines, backup);
                throw new ActivationFailedException("Logstash 配置校验失败(" + s.id + "),已回滚");
            }
            deployer.restartLogstash();
        } catch (ActivationFailedException e) {
            throw e;
        } catch (IOException e) {
            rollback(confFile, pipelines, backup);
            throw new ActivationFailedException("生效失败(写入/同步): " + e.getMessage(), e);
        } catch (RuntimeException e) {
            rollback(confFile, pipelines, backup);
            throw new ActivationFailedException("生效失败(同步/重启): " + e.getMessage(), e);
        }
    }

    /** 回滚:删除新 conf,还原 pipelines.yml 原内容。失败不掩盖原始错误。 */
    private void rollback(Path confFile, Path pipelines, String backup) {
        try {
            Files.deleteIfExists(confFile);
            if (backup != null) {
                if (backup.isEmpty()) {
                    Files.deleteIfExists(pipelines);
                } else {
                    Files.writeString(pipelines, backup);
                }
            }
        } catch (IOException e) {
            System.err.println("[ActivationCoordinator] 回滚失败: " + e.getMessage());
        }
    }

    private static String pipelineId(LogSource s) {
        return s.id;
    }
}
