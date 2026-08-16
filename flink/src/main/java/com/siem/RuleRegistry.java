package com.siem;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

/**
 * 规则注册表:集中管理检测规则,新增规则在此追加即可(多规则能力的载体)。
 *
 * 当前管道只有 SSH 认证失败事件(event.action=authentication_failure),
 * 单事件规则靠 user.name / source.ip 区分。
 *
 * 元数据约定:
 * - riskScore 按危害定值(0-100),用于告警排序与实体风险聚合;
 * - tags 为 MITRE ATT&amp;CK 技术 ID(如 attack.t1110.001),用于覆盖度分析。
 */
public class RuleRegistry implements Serializable {

    private final List<Rule> rules;

    public RuleRegistry() {
        this.rules = Arrays.asList(
                new Rule(
                        "rule-ssh-auth-failure-001",
                        "SSH 认证失败",
                        "ssh_authentication_failure",
                        "medium",
                        "检测到 SSH 认证失败",
                        new FieldEqualsCondition("event.action", "authentication_failure"),
                        40,
                        List.of("attack.t1110.001"),
                        "experimental",
                        "1.0"
                ),
                new Rule(
                        "rule-root-login-failure-001",
                        "root 账号认证失败",
                        "ssh_root_login_failure",
                        "high",
                        "检测到 root 账号 SSH 认证失败(特权账号被攻击)",
                        new AllCondition(
                                new FieldEqualsCondition("event.action", "authentication_failure"),
                                new FieldEqualsCondition("user.name", "root")
                        ),
                        81,
                        List.of("attack.t1078.002", "attack.t1068"),
                        "experimental",
                        "1.0"
                ),
                new Rule(
                        "rule-common-user-bruteforce-001",
                        "常见账号被爆破",
                        "ssh_common_user_bruteforce",
                        "high",
                        "检测到常见弱账号(admin/test/guest 等)被尝试登录",
                        new AllCondition(
                                new FieldEqualsCondition("event.action", "authentication_failure"),
                                new FieldInCondition("user.name", "admin", "administrator",
                                        "test", "guest", "postgres", "ubuntu", "oracle")
                        ),
                        47,
                        List.of("attack.t1078.002"),
                        "experimental",
                        "1.0"
                )
        );
    }

    public List<Rule> getRules() {
        return rules;
    }
}
