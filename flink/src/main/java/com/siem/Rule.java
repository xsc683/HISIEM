package com.siem;

import java.io.Serializable;

/**
 * 检测规则元数据 + 判定条件。
 * 告警直接引用规则的 id/name/type/severity/description。
 */
public class Rule implements Serializable {

    private final String id;
    private final String name;
    private final String type;
    private final String severity;
    private final String description;
    private final Condition condition;

    public Rule(String id, String name, String type, String severity,
                String description, Condition condition) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.severity = severity;
        this.description = description;
        this.condition = condition;
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
}
