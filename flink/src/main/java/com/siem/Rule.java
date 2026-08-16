package com.siem;

import java.io.Serializable;
import java.util.List;

/**
 * 检测规则元数据 + 判定条件。
 * 告警直接引用规则的 id/name/type/severity/description,以及
 * 风险评分(riskScore)、MITRE ATT&amp;CK 标注(tags)、状态(status)等元数据。
 */
public class Rule implements Serializable {

    private final String id;
    private final String name;
    private final String type;
    private final String severity;
    private final String description;
    private final Condition condition;
    /** 数值风险分(0-100),用于告警排序与实体风险聚合。 */
    private final int riskScore;
    /** MITRE ATT&amp;CK 技术 ID,如 attack.t1110.001。 */
    private final List<String> tags;
    /** 规则状态:experimental / stable / deprecated。 */
    private final String status;
    private final String version;

    public Rule(String id, String name, String type, String severity,
                String description, Condition condition) {
        this(id, name, type, severity, description, condition, 0, List.of(), "experimental", "1.0");
    }

    public Rule(String id, String name, String type, String severity,
                String description, Condition condition,
                int riskScore, List<String> tags, String status, String version) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.severity = severity;
        this.description = description;
        this.condition = condition;
        this.riskScore = riskScore;
        this.tags = tags;
        this.status = status;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }

    public Condition getCondition() {
        return condition;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getStatus() {
        return status;
    }

    public String getVersion() {
        return version;
    }
}
