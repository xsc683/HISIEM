package com.xscsiem.hsiem_platform.onboarding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Story 01 生效协调:写 conf + 注册 pipeline → 同步 → 校验 → 重启;失败回滚。 */
class ActivationCoordinatorTest {

    @TempDir
    Path temp;

    private LogstashDeployer deployer;
    private ActivationCoordinator coordinator;
    private LogSource source;
    private ParserTemplate template;

    @BeforeEach
    void setUp() throws Exception {
        deployer = mock(LogstashDeployer.class);
        coordinator = new ActivationCoordinator(new LogstashConfigGenerator(), deployer,
                temp.resolve("pipeline").toString(), temp.resolve("config").toString(),
                "/usr/share/logstash/pipeline",
                temp.resolve("docker-compose.yml").toString());
        // mock compose:含 logstash 基准端口 5000(端口映射插入锚点)
        Files.createDirectories(temp.resolve("pipeline"));
        Files.writeString(temp.resolve("docker-compose.yml"),
                "services:\n  logstash:\n    ports:\n      - \"5000:5000\"\n");
        source = LogSource.creating("ssh-web-01", "tcp", "ssh-auth", 5001);
        source.id = "ls-abc12345";
        source.sourceId = "ls-abc12345";
        template = new ParserTemplate();
        template.id = "ssh-auth";
        template.patterns = List.of("x");
    }

    @Test
    void success_writesConfAndRegistersPipeline_thenSyncValidateRestart() throws Exception {
        when(deployer.validateConfig(anyString())).thenReturn(true);

        coordinator.activate(source, template);

        Path conf = temp.resolve("pipeline/log-sources/ls-abc12345.conf");
        assertTrue(Files.exists(conf), "conf 文件应写入");
        String content = Files.readString(conf);
        assertTrue(content.contains("port => 5001"));
        assertTrue(content.contains("log.source_id"));

        Path pipelines = temp.resolve("config/pipelines.yml");
        assertTrue(Files.exists(pipelines), "pipelines.yml 应注册新 pipeline");
        String yml = Files.readString(pipelines);
        assertTrue(yml.contains("pipeline.id: ls-abc12345"));
        assertTrue(yml.contains("/usr/share/logstash/pipeline/log-sources/ls-abc12345.conf"));

        // 路线B:compose 端口映射应加入
        String compose = Files.readString(temp.resolve("docker-compose.yml"));
        assertTrue(compose.contains("\"5001:5001\""), "compose 应含数据源端口映射 5001:5001");

        verify(deployer).syncLogstash();
        verify(deployer).restartLogstash();
    }

    @Test
    void validationFailure_rollsBackFiles_andThrows() throws Exception {
        Files.createDirectories(temp.resolve("config"));
        Files.writeString(temp.resolve("config/pipelines.yml"),
                "- pipeline.id: main\n  path.config: \"/x/*.conf\"\n");
        when(deployer.validateConfig(anyString())).thenReturn(false);

        assertThrows(ActivationFailedException.class, () -> coordinator.activate(source, template));

        assertFalse(Files.exists(temp.resolve("pipeline/log-sources/ls-abc12345.conf")), "conf 应回滚删除");
        String yml = Files.readString(temp.resolve("config/pipelines.yml"));
        assertFalse(yml.contains("ls-abc12345"), "pipeline 条目应回滚");
        assertTrue(yml.contains("pipeline.id: main"), "原 pipelines.yml 应还原");
        // 路线B:compose 端口映射也应回滚(还原为仅 5000)
        String compose = Files.readString(temp.resolve("docker-compose.yml"));
        assertFalse(compose.contains("5001:5001"), "compose 端口映射应回滚");
        assertTrue(compose.contains("5000:5000"), "原 compose 应还原");
        verify(deployer, never()).restartLogstash();
    }

    @Test
    void restartFailure_rollsBackFiles_andThrows() throws Exception {
        when(deployer.validateConfig(anyString())).thenReturn(true);
        doThrow(new IllegalStateException("restart failed")).when(deployer).restartLogstash();

        assertThrows(ActivationFailedException.class, () -> coordinator.activate(source, template));
        assertFalse(Files.exists(temp.resolve("pipeline/log-sources/ls-abc12345.conf")), "重启失败也应回滚");
    }
}
