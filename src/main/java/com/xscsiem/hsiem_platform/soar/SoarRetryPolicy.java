package com.xscsiem.hsiem_platform.soar;

import java.time.Duration;

public record SoarRetryPolicy(int maxAttempts, Duration initialDelay,
                              double backoffMultiplier, Duration maxDelay) {

    private static final int MAX_ATTEMPTS = 10;
    private static final long MAX_DELAY_SECONDS = Duration.ofHours(1).toSeconds();

    public static SoarRetryPolicy resolve(PlaybookGraph.Node node, SoarNodeHandler handler) {
        PlaybookGraph.ExecutionPolicy configured = node.policy();
        int attempts = configured.maxAttempts() == 0 ? handler.defaultMaxAttempts() : configured.maxAttempts();
        validate(configured, attempts);
        return new SoarRetryPolicy(attempts, Duration.ofSeconds(configured.initialDelaySeconds()),
                configured.backoffMultiplier(), Duration.ofSeconds(configured.maxDelaySeconds()));
    }

    public static void validate(PlaybookGraph.ExecutionPolicy policy, int effectiveAttempts) {
        if (effectiveAttempts < 1 || effectiveAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("节点最大执行次数必须在 1 到 " + MAX_ATTEMPTS + " 之间");
        }
        if (policy.initialDelaySeconds() < 1 || policy.initialDelaySeconds() > MAX_DELAY_SECONDS) {
            throw new IllegalArgumentException("节点初始重试延迟必须在 1 秒到 1 小时之间");
        }
        if (policy.backoffMultiplier() < 1.0 || policy.backoffMultiplier() > 10.0) {
            throw new IllegalArgumentException("节点重试退避倍率必须在 1 到 10 之间");
        }
        if (policy.maxDelaySeconds() < policy.initialDelaySeconds()
                || policy.maxDelaySeconds() > MAX_DELAY_SECONDS) {
            throw new IllegalArgumentException("节点最大重试延迟必须不小于初始延迟且不超过 1 小时");
        }
    }

    public Duration delayAfter(int failedAttempt) {
        double seconds = initialDelay.toSeconds() * Math.pow(backoffMultiplier, Math.max(0, failedAttempt - 1));
        return Duration.ofSeconds(Math.min((long) seconds, maxDelay.toSeconds()));
    }
}
