package com.xscsiem.hsiem_platform.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 生产实现:通过 wsl 运行 entity-risk.py(重算实体风险,读最新 asset-criticality.json)。 */
@Component
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
                "python3 " + wslRepoPath + "/infra/elasticsearch/entity-risk.py");
    }

    private String runOut(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("entity-risk.py 超时");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("entity-risk.py 执行失败:\n" + out);
            }
            return out;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("entity-risk.py 执行异常", e);
        }
    }
}
