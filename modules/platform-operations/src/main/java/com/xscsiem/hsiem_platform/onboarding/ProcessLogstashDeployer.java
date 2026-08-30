package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final String composeName;

    public ProcessLogstashDeployer(
            @Value("${app.logstash.wsl-repo-path:/mnt/d/Project/SIEM}") String wslRepoPath,
            @Value("${app.logstash.deploy-dir:~/projects/mini-siem}") String deployDir,
            @Value("${app.logstash.container-name:siem-logstash}") String containerName,
            @Value("${app.logstash.compose-name:docker-compose.yml}") String composeName) {
        this.wslRepoPath = wslRepoPath;
        this.deployDir = deployDir;
        this.containerName = containerName;
        this.composeName = composeName;
    }

    @Override
    public void syncLogstash() {
        // rsync -a --delete:原地同步,保留目录本身(避免 bind mount 失效)
        if (!exitOk("wsl", "bash", "-c",
                "rsync -a --delete " + wslRepoPath + "/infra/logstash/ " + deployDir + "/logstash/")) {
            throw new IllegalStateException("同步 logstash 到 WSL 失败");
        }
        // 同步 docker-compose.yml(数据源端口映射:生效时 coordinator 已更新 repo 侧 compose)
        if (!exitOk("wsl", "bash", "-c",
                "cp " + wslRepoPath + "/infra/" + composeName + " " + deployDir + "/" + composeName)) {
            throw new IllegalStateException("同步 docker-compose.yml 到 WSL 失败");
        }
    }

    @Override
    public boolean validateConfig(String containerConfigPath) {
        // --path.data 重定向到临时目录:运行中的 Logstash 实例已持有 data/queue/*.lock,
        // 直接校验会因队列锁冲突而失败(配置本身是合法的)。每次用唯一后缀避免并发校验互相干扰。
        String tmpData = "/tmp/ls-validate-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return exitOk("docker", "exec", containerName, "logstash",
                "--config.test_and_exit", "-f", containerConfigPath, "--path.data=" + tmpData);
    }

    @Override
    public void restartLogstash() {
        // 用 docker compose up -d 重建容器:生效时新增了数据源端口映射,需重建才生效。
        // 部署目录里已有同步来的 compose(含最新 ports);compose 重建会保留命名卷与健康检查。
        // 失败时回退 docker restart(至少让配置变更生效,端口映射缺失可后续 compose up 补)。
        String composeFile = deployDir + "/" + composeName;
        if (!exitOk("wsl", "bash", "-c",
                "cd " + deployDir + " && COMPOSE_PROJECT_NAME=\"${COMPOSE_PROJECT_NAME:-infra}\" "
                        + "docker compose -f " + composeName + " up -d logstash")) {
            throw new IllegalStateException("重建 Logstash 容器失败: " + composeFile
                    + "(配置已同步,可手动 docker compose up -d logstash)");
        }
    }

    @Override
    public void reloadLogstash() {
        if (!exitOk("docker", "exec", containerName, "kill", "-HUP", "1")) {
            throw new IllegalStateException("热加载 Logstash pipeline 失败");
        }
    }

    private boolean exitOk(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // 并发读取输出,避免子进程输出管道写满后导致 waitFor 永久阻塞。
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
}
