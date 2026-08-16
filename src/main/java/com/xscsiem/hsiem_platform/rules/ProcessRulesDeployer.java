package com.xscsiem.hsiem_platform.rules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生产实现:通过进程调起 docker/wsl 完成规则部署生效。
 * - syncRules: docker cp infra/rules → jobmanager:/opt/flink/rules(经 wsl 访问仓库 WSL 路径)。
 * - restartDetectionJob: 查运行中 job → cancel 带 savepoint → 找最新 savepoint → run -s 恢复。
 *   幂等:无运行中 job 时直接 run(全新提交)。
 */
@Component
public class ProcessRulesDeployer implements RulesDeployer {

    private static final Pattern RUNNING_JOB = Pattern.compile(
            "\\b([0-9a-f]{32})\\b[^\\n]*\\(RUNNING\\)");
    private static final Pattern SUBMITTED_JOB = Pattern.compile(
            "JobID ([0-9a-f]{32})");
    private static final long TIMEOUT_SECONDS = 120;

    private final String wslRepoPath;
    private final String containerName;
    private final String jarName;
    private final String savepointDir;

    public ProcessRulesDeployer(
            @Value("${app.flink.wsl-repo-path:/mnt/d/Project/SIEM}") String wslRepoPath,
            @Value("${app.flink.container-name:siem-flink-jobmanager}") String containerName,
            @Value("${app.flink.jar-name:detection-job-1.0.jar}") String jarName,
            @Value("${app.flink.savepoint-dir:file:///opt/flink/savepoints}") String savepointDir) {
        this.wslRepoPath = wslRepoPath;
        this.containerName = containerName;
        this.jarName = jarName;
        this.savepointDir = savepointDir;
    }

    @Override
    public void syncRules() {
        run("wsl", "bash", "-c",
                "docker cp " + wslRepoPath + "/infra/rules/. " + containerName + ":/opt/flink/rules/");
    }

    @Override
    public String restartDetectionJob() {
        String runningJob = findRunningJob();
        if (runningJob == null) {
            // 无运行中 job → 全新提交(规则已同步,启动即读取)
            return submittedJobId(runOut("docker", "exec", containerName, "flink",
                    "run", "-d", "/opt/flink/" + jarName));
        }
        // cancel 带 savepoint(保留状态),从最新 savepoint 恢复(重新读取规则)
        run("docker", "exec", containerName, "flink", "cancel", "-s", savepointDir, runningJob);
        String sp = latestSavepoint();
        if (sp == null) {
            throw new IllegalStateException("未找到 savepoint,取消 job 后无法恢复");
        }
        return submittedJobId(runOut("docker", "exec", containerName, "flink",
                "run", "-d", "-s", savepointDir + "/" + sp, "/opt/flink/" + jarName));
    }

    private String findRunningJob() {
        String out = runOut("docker", "exec", containerName, "flink", "list");
        Matcher m = RUNNING_JOB.matcher(out);
        return m.find() ? m.group(1) : null;
    }

    private String latestSavepoint() {
        String out = runOut("docker", "exec", containerName, "sh", "-c",
                "ls -t /opt/flink/savepoints | head -1");
        return out.isBlank() ? null : out.trim();
    }

    private static String submittedJobId(String out) {
        Matcher m = SUBMITTED_JOB.matcher(out);
        return m.find() ? m.group(1) : "unknown";
    }

    private void run(String... cmd) {
        runOut(cmd);
    }

    /** 执行命令并返回 stdout(trim)。退出码非 0 抛 IllegalStateException。 */
    private String runOut(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("命令超时: " + String.join(" ", cmd));
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("命令失败: " + String.join(" ", cmd) + "\n" + out);
            }
            return out;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令执行异常: " + String.join(" ", cmd), e);
        }
    }
}
