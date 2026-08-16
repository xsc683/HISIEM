package com.siem.config;

import com.siem.AllCondition;
import com.siem.AnyCondition;
import com.siem.Condition;
import com.siem.FieldEqualsCondition;
import com.siem.FieldInCondition;
import com.siem.NotCondition;
import com.siem.Rule;
import com.siem.RuleMeta;
import com.siem.WindowRule;

import java.util.List;

/**
 * 把规则声明(RuleDecl)构建为运行时对象:
 * - single_event → {@link Rule}
 * - window → {@link WindowRule}
 * - cep / baseline → {@link RuleMeta}(序列 Pattern 与基线参数在 DetectionJob 按 step 构建)
 * 条件声明 → {@link Condition}(field_equals / field_in / all / any / not)。
 */
public class RuleBuilder {

    public Rule toRule(RuleDecl d) {
        return new Rule(d.id, d.name, d.type, d.severity, d.description,
                buildCondition(d.condition), intValue(d.riskScore, 0),
                d.tags == null ? List.of() : d.tags,
                d.status == null ? "experimental" : d.status,
                d.version == null ? "1.0" : d.version);
    }

    public WindowRule toWindowRule(RuleDecl d) {
        if (d.keyField == null || d.windowMinutes == null || d.threshold == null) {
            throw new IllegalArgumentException("window 规则缺少 keyField/windowMinutes/threshold: " + d.id);
        }
        return new WindowRule(d.id, d.name, d.type, d.severity, d.description,
                d.keyField, buildCondition(d.condition), d.windowMinutes, d.threshold,
                intValue(d.riskScore, 0),
                d.tags == null ? List.of() : d.tags,
                d.status == null ? "experimental" : d.status,
                d.version == null ? "1.0" : d.version,
                d.slidingMinutes);
    }

    public RuleMeta toMeta(RuleDecl d) {
        return new RuleMeta(d.id, d.name, d.type, d.severity, d.description,
                intValue(d.riskScore, 0),
                d.tags == null ? List.of() : d.tags,
                d.status == null ? "experimental" : d.status,
                d.version == null ? "1.0" : d.version);
    }

    /** 条件声明 → Condition 接口实现(递归支持 all/any/not 嵌套)。 */
    public static Condition buildCondition(RuleDecl.ConditionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("规则缺少 condition");
        }
        return switch (spec.type) {
            case "field_equals" -> new FieldEqualsCondition(spec.field, spec.value);
            case "field_in" -> {
                if (spec.values == null || spec.values.isEmpty()) {
                    throw new IllegalArgumentException("field_in 条件缺少 values: " + spec.field);
                }
                yield new FieldInCondition(spec.field, spec.values.toArray());
            }
            case "all" -> new AllCondition(subConditions(spec, "all"));
            case "any" -> new AnyCondition(subConditions(spec, "any"));
            case "not" -> {
                if (spec.conditions == null || spec.conditions.size() != 1) {
                    throw new IllegalArgumentException("not 条件需要恰好一个子条件");
                }
                yield new NotCondition(buildCondition(spec.conditions.get(0)));
            }
            default -> throw new IllegalArgumentException("未知条件类型: " + spec.type);
        };
    }

    private static Condition[] subConditions(RuleDecl.ConditionSpec spec, String type) {
        if (spec.conditions == null || spec.conditions.isEmpty()) {
            throw new IllegalArgumentException(type + " 条件缺少子条件");
        }
        return spec.conditions.stream().map(RuleBuilder::buildCondition).toArray(Condition[]::new);
    }

    private static int intValue(Integer v, int def) {
        return v == null ? def : v;
    }
}
