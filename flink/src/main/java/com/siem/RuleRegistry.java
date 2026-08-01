package com.siem;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

/**
 * 规则注册表:集中管理检测规则,新增规则在此追加即可(多规则能力的载体)。
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
                        new FieldEqualsCondition("event.action", "authentication_failure")
                )
        );
    }

    public List<Rule> getRules() {
        return rules;
    }
}
