package com.xscsiem.hsiem_platform.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 生产实现:通过 wsl 运行 entity-risk.py(重算实体风险,读最新 asset-criticality.json)。 */
@Component
@ConditionalOnProperty(name = "app.operations.process-adapters", havingValue = "enabled")
public class ProcessCriticalityDeployer implements CriticalityDeployer {

    private static final long TIMEOUT_SECONDS = 120;
    private final String wslRepoPath;

    public ProcessCriticalityDeployer(
            @Value("${app.flink.wsl-repo-path:/mnt/d/Project/SIEM}") String wslRepoPath) {
        this.wslRepoPath = wslRepoPath;
    }

    @Override
    public String recalcEntityRisk() {
        return runOut("wsl", "bash", "-c",
                "python3 " + wslRepoPath + "/infra/elasticsearch/entity-risk.py --write");
    }

    private String runOut(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
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
                throw new IllegalStateException("entity-risk.py 超时");
            }
            String out;
            try {
                out = output.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                out = "[读取 entity-risk.py 输出失败] " + e.getMessage();
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("entity-risk.py 执行失败:\n" + out);
            }
            return out.replace("\u0000", "");
        } catch (IOException e) {
            throw new IllegalStateException("entity-risk.py 执行异常", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("entity-risk.py 执行异常", e);
        }
    }
}
