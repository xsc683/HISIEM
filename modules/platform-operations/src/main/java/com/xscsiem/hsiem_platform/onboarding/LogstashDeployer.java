package com.xscsiem.hsiem_platform.onboarding;

/**
 * 生效链路的外部命令抽象(Story 01 FR-3)。生产实现 {@link ProcessLogstashDeployer}
 * 通过进程调起 wsl/docker;单元测试用 mock 实现,不依赖运行环境。
 */
public interface LogstashDeployer {

    /** 把 infra/logstash 同步到 WSL 部署目录(rsync 原地同步,不 rm -rf bind mount 目录)。 */
    void syncLogstash();

    /** 在 Logstash 容器内校验指定 pipeline 配置(--config.test_and_exit)。通过返回 true。 */
    boolean validateConfig(String containerConfigPath);

    /** 重启 Logstash 容器使新 pipeline 生效。 */
    void restartLogstash();

    /** 重新加载 pipeline；默认回退到重启，生产实现对 file pipeline 使用 HUP。 */
    default void reloadLogstash() {
        restartLogstash();
    }
}
