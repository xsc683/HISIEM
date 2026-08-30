package com.xscsiem.hsiem_platform.soar;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SoarDictionary {

    private static final List<OperatorDefinition> TEXT_OPERATORS = List.of(
            op("eq", "=="), op("ne", "!="), op("contains", "包含"),
            op("is_empty", "为空"), op("not_empty", "不为空"));
    private static final List<OperatorDefinition> NUMBER_OPERATORS = List.of(
            op("eq", "=="), op("ne", "!="), op("gt", ">"), op("lt", "<"),
            op("is_empty", "为空"), op("not_empty", "不为空"));
    private static final List<OperatorDefinition> LIST_OPERATORS = List.of(
            op("contains", "包含"), op("is_empty", "为空"), op("not_empty", "不为空"));

    private static final List<FieldDefinition> ALERT_FIELDS = List.of(
            text("alert.id", "告警 ID"), text("alert.rule_id", "规则 ID"),
            text("alert.rule_name", "规则名称"), text("alert.severity", "严重级别"),
            text("alert.status", "告警状态"), text("alert.verdict", "研判结论"),
            number("alert.risk_score", "风险分"), text("alert.source_ip", "源 IP"),
            text("alert.user_name", "用户名"), text("alert.host_name", "主机名"),
            text("alert.timestamp", "事件时间"));
    private static final List<FieldDefinition> CASE_FIELDS = List.of(
            text("case.id", "案件 ID"), text("case.title", "案件标题"),
            text("case.status", "案件状态"), text("case.verdict", "案件结论"),
            text("case.owner", "负责人"), list("case.alert_ids", "告警 ID 列表"));

    private static final List<ActionDefinition> ALERT_ACTIONS = List.of(
            action("alert.update_status", "更新告警状态", List.of(
                    param("status", "状态", "select", true,
                            List.of("open", "acknowledged", "investigating", "resolved", "closed")))),
            action("alert.update_verdict", "更新告警结论", List.of(
                    param("verdict", "结论", "select", true,
                            List.of("true_positive", "false_positive", "duplicate")))),
            action("alert.create_case", "从告警创建案件", List.of(
                    param("title", "案件标题", "text", true, List.of()))),
            action("alert.add_to_case", "加入已有案件", List.of(
                    param("case_id", "案件 ID", "text", true, List.of()))));
    private static final List<ActionDefinition> CASE_ACTIONS = List.of(
            action("case.update_status", "更新案件状态", List.of(
                    param("status", "状态", "select", true, List.of("open", "investigating", "resolved")))),
            action("case.close", "关闭案件", List.of(
                    param("verdict", "结论", "select", true,
                            List.of("true_positive", "false_positive", "duplicate")))),
            action("case.add_alert", "添加告警", List.of(
                    param("alert_id", "告警 ID", "text", true, List.of()))),
            action("case.update_owner", "更新负责人", List.of(
                    param("owner", "负责人", "text", true, List.of()))),
            action("case.add_evidence", "添加证据", List.of(
                    param("type", "证据类型", "text", true, List.of()),
                    param("value", "证据内容", "text", true, List.of()))));

    public List<FieldDefinition> fields(String objectType) {
        return switch (normalizeObjectType(objectType)) {
            case "alert" -> ALERT_FIELDS;
            case "case" -> CASE_FIELDS;
            default -> throw new IllegalArgumentException("objectType 仅支持 alert 或 case");
        };
    }

    public List<ActionDefinition> actions(String objectType) {
        return switch (normalizeObjectType(objectType)) {
            case "alert" -> ALERT_ACTIONS;
            case "case" -> CASE_ACTIONS;
            default -> throw new IllegalArgumentException("objectType 仅支持 alert 或 case");
        };
    }

    public FieldDefinition field(String objectType, String path) {
        return fields(objectType).stream().filter(item -> item.path().equals(path)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("条件字段不在生命周期字段字典中: " + path));
    }

    public ActionDefinition action(String objectType, String id) {
        return actions(objectType).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("业务动作不支持当前对象类型: " + id));
    }

    private String normalizeObjectType(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static FieldDefinition text(String path, String label) {
        return new FieldDefinition(path, label, "text", TEXT_OPERATORS);
    }

    private static FieldDefinition number(String path, String label) {
        return new FieldDefinition(path, label, "number", NUMBER_OPERATORS);
    }

    private static FieldDefinition list(String path, String label) {
        return new FieldDefinition(path, label, "list", LIST_OPERATORS);
    }

    private static OperatorDefinition op(String id, String label) {
        return new OperatorDefinition(id, label);
    }

    private static ActionDefinition action(String id, String label, List<ParameterDefinition> parameters) {
        return new ActionDefinition(id, label, parameters);
    }

    private static ParameterDefinition param(String id, String label, String type,
                                             boolean required, List<String> options) {
        return new ParameterDefinition(id, label, type, required, options);
    }

    public record FieldDefinition(String path, String label, String type,
                                  List<OperatorDefinition> operators) {
    }

    public record OperatorDefinition(String id, String label) {
    }

    public record ActionDefinition(String id, String label, List<ParameterDefinition> parameters) {
    }

    public record ParameterDefinition(String id, String label, String type,
                                      boolean required, List<String> options) {
    }
}
