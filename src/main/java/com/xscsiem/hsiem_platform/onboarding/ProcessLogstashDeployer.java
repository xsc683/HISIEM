package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 生产实现:通过进程调起外部命令完成生效链路。
 * - syncLogstash:  wsl rsync infra/logstash → ~/projects/mini-siem/logstash(只同步 logstash,
 *   不走 deploy.sh 全量,避免每次激活都触发 Flink 重建)。
 * - validateConfig: docker exec siem-logstash logstash --config.test_and_exit -f <conf>。
 * - restartLogstash: docker restart siem-logstash。
 * 命令路径(仓库 WSL 路径 / 部署目录 / 容器名)可由 application.properties 覆盖。
 */
@Component
public class ProcessLogstashDeployer implements LogstashDeployer {

    private static final long TIMEOUT_SECONDS = 120;

    private final String wslRepoPath;
    private final String deployDir;
    private final String containerName;

    public ProcessLogstashDeployer(
            @Value("${app.logstash.wsl-repo-path:/mnt/d/Project/SIEM}") String wslRepoPath,
            @Value("${app.logstash.deploy-dir:~/projects/mini-siem}") String deployDir,
            @Value("${app.logstash.container-name:siem-logstash}") String containerName) {
        this.wslRepoPath = wslRepoPath;
        this.deployDir = deployDir;
        this.containerName = containerName;
    }

    @Override
    public void syncLogstash() {
        // rsync -a --delete:原地同步,保留目录本身(避免 bind mount 失效)
        if (!exitOk("wsl", "bash", "-c",
                "rsync -a --delete " + wslRepoPath + "/infra/logstash/ " + deployDir + "/logstash/")) {
            throw new IllegalStateException("同步 logstash 到 WSL 失败");
        }
    }

    @Override
    public boolean validateConfig(String containerConfigPath) {
        return exitOk("docker", "exec", containerName, "logstash",
                "--config.test_and_exit", "-f", containerConfigPath);
    }

    @Override
    public void restartLogstash() {
        if (!exitOk("docker", "restart", containerName)) {
            throw new IllegalStateException("重启 Logstash 容器失败: " + containerName);
        }
    }

    private boolean exitOk(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // 顺序读输出(阻塞到进程结束),便于排查
            p.getInputStream().transferTo(System.out);
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
