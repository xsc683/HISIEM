package com.xscsiem.hsiem_platform.onboarding;

/**
 * 生效失败(Logstash 配置校验失败 / 同步失败 / 重启失败)。
 * 由 ActivationCoordinator 抛出,已回滚文件(删除 conf + 还原 pipelines.yml);
 * 由 LogSourceService 捕获并将状态置为 failed。
 */
public class ActivationFailedException extends RuntimeException {
    public ActivationFailedException(String message) {
        super(message);
    }

    public ActivationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
