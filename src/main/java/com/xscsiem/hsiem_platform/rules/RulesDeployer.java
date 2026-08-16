package com.xscsiem.hsiem_platform.rules;

/**
 * 规则部署生效的外部命令抽象(story-03)。生产实现 {@link ProcessRulesDeployer}
 * 通过进程调起 docker/wsl;单元测试 mock。
 */
public interface RulesDeployer {

    /** 同步 infra/rules → Flink jobmanager /opt/flink/rules(检测 job 启动读取)。 */
    void syncRules();

    /** 重启检测 job(启停生效):cancel 带 savepoint → 从最新 savepoint 恢复。返回新 job id。 */
    String restartDetectionJob();
}
