package com.siem;

import java.io.Serializable;
import java.util.List;

/**
 * 规则元数据(告警输出用),由 {@link com.siem.config.RuleDecl} 构建,
 * 传给 CEP({@link BruteforceSuccessFunction})与基线({@link BaselineAnomalyFunction})等
 * 无法直接用 {@link Rule} 表达的函数。
 */
public record RuleMeta(String id, String name, String type, String severity,
                       String description, int riskScore, List<String> tags,
                       String status, String version) implements Serializable {
}
