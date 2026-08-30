package com.siem.config;

import com.siem.Condition;
import com.siem.WindowRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则 YAML 加载与声明 → 运行时对象构建(检测即代码,story-03)。 */
class RuleConfigLoaderTest {

    @TempDir
    Path temp;

    private void write(String name, String yaml) throws IOException {
        Files.writeString(temp.resolve(name), yaml);
    }

    @Test
    void loadDir_parsesSingleEventRule() throws IOException {
        write("rule-a.yaml", """
                id: rule-a
                name: 测试规则 A
                category: single_event
                type: ssh_auth
                enabled: true
                severity: medium
                description: desc
                riskScore: 40
                tags: [attack.t1110.001]
                status: experimental
                version: "1.0"
                condition:
                  type: field_equals
                  field: event.action
                  value: authentication_failure
                """);
        List<RuleDecl> decls = new RuleConfigLoader().loadDir(temp.toString());
        assertEquals(1, decls.size());
        RuleDecl d = decls.get(0);
        assertEquals("rule-a", d.id);
        assertEquals("single_event", d.category);
        assertEquals("ssh_auth", d.type);
        assertTrue(d.enabled);
        assertEquals(40, d.riskScore);
        assertEquals("field_equals", d.condition.type);
        assertEquals("event.action", d.condition.field);
        assertEquals("authentication_failure", d.condition.value);
    }

    @Test
    void loadEnabled_filtersDisabled() throws IOException {
        write("rule-a.yaml", """
                id: a
                category: single_event
                type: t
                enabled: true
                condition: {type: field_equals, field: f, value: v}
                """);
        write("rule-b.yaml", """
                id: b
                category: single_event
                type: t
                enabled: false
                condition: {type: field_equals, field: f, value: v}
                """);
        List<RuleDecl> enabled = new RuleConfigLoader().loadEnabled(temp.toString());
        assertEquals(1, enabled.size());
        assertEquals("a", enabled.get(0).id);
    }

    @Test
    void loadDir_sortsFilesByNameAndRejectsDuplicateIds() throws IOException {
        write("z-rule.yaml", """
                id: same
                category: single_event
                type: t
                enabled: true
                condition: {type: field_equals, field: f, value: v}
                """);
        write("a-rule.yaml", """
                id: first
                category: single_event
                type: t
                enabled: true
                condition: {type: field_equals, field: f, value: v}
                """);

        List<RuleDecl> sorted = new RuleConfigLoader().loadDir(temp.toString());
        assertEquals(List.of("first", "same"), sorted.stream().map(rule -> rule.id).toList());

        write("another-rule.yaml", """
                id: same
                category: single_event
                type: t
                enabled: true
                condition: {type: field_equals, field: f, value: v}
                """);
        assertThrows(IllegalStateException.class, () -> new RuleConfigLoader().loadDir(temp.toString()));
    }

    @Test
    void loadDir_missingDir_throws() {
        assertThrows(IllegalStateException.class,
                () -> new RuleConfigLoader().loadDir(temp.resolve("nope").toString()));
    }

    @Test
    void buildCondition_fieldEquals_fieldIn_all() {
        Condition eq = RuleBuilder.buildCondition(
                spec("field_equals", "event.action", "authentication_failure", null, null));
        assertTrue(eq.matches(Map.of("event.action", "authentication_failure")));
        assertFalse(eq.matches(Map.of("event.action", "authentication_success")));

        RuleDecl.ConditionSpec in = spec("field_in", "user.name", null,
                List.of("root", "admin"), null);
        Condition cin = RuleBuilder.buildCondition(in);
        assertTrue(cin.matches(Map.of("user.name", "root")));
        assertFalse(cin.matches(Map.of("user.name", "other")));

        RuleDecl.ConditionSpec all = spec("all", null, null, null, List.of(
                spec("field_equals", "event.action", "authentication_failure", null, null),
                spec("field_equals", "user.name", "root", null, null)));
        Condition call = RuleBuilder.buildCondition(all);
        assertTrue(call.matches(Map.of("event.action", "authentication_failure", "user.name", "root")));
        assertFalse(call.matches(Map.of("event.action", "authentication_failure", "user.name", "bob")));

        // any(OR)与 not
        RuleDecl.ConditionSpec any = spec("any", null, null, null, List.of(
                spec("field_equals", "event.action", "authentication_failure", null, null),
                spec("field_equals", "event.action", "authentication_success", null, null)));
        assertTrue(RuleBuilder.buildCondition(any)
                .matches(Map.of("event.action", "authentication_success")));

        RuleDecl.ConditionSpec not = spec("not", null, null, null, List.of(
                spec("field_equals", "event.action", "authentication_failure", null, null)));
        assertTrue(RuleBuilder.buildCondition(not)
                .matches(Map.of("event.action", "authentication_success")));
        assertFalse(RuleBuilder.buildCondition(not)
                .matches(Map.of("event.action", "authentication_failure")));
    }

    @Test
    void buildCondition_unknownType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> RuleBuilder.buildCondition(spec("bogus", "f", "v", null, null)));
    }

    @Test
    void toWindowRule_parsesParams() throws IOException {
        write("rule-win.yaml", """
                id: rule-win
                name: 窗口规则
                category: window
                type: ssh_brute
                enabled: true
                severity: critical
                description: d
                riskScore: 73
                tags: [attack.t1110.001]
                keyField: source.ip
                windowMinutes: 5
                slidingMinutes: 1
                threshold: 5
                condition: {type: field_equals, field: event.action, value: authentication_failure}
                """);
        RuleDecl d = new RuleConfigLoader().loadDir(temp.toString()).get(0);
        RuleBuilder b = new RuleBuilder();
        WindowRule wr = b.toWindowRule(d);
        assertEquals("rule-win", wr.getId());
        assertEquals("ssh_brute", wr.getType());
        assertEquals("source.ip", wr.getKeyField());
        assertEquals(5, wr.getWindowMinutes());
        assertEquals(5, wr.getThreshold());
        assertEquals(73, wr.getRiskScore());
        // F7:slidingMinutes 声明 → WindowRule 传递
        assertEquals(Long.valueOf(1), wr.getSlidingMinutes());
        assertTrue(wr.getSlidingMinutes() > 0, "滑动规则 slidingMinutes 应 > 0");
    }

    @Test
    void toWindowRule_slidingNull_byDefault() throws IOException {
        // 未声明 slidingMinutes → null(tumbling 固定窗口)
        write("rule-win2.yaml", """
                id: rule-win2
                category: window
                type: t
                keyField: source.ip
                windowMinutes: 5
                threshold: 5
                condition: {type: field_equals, field: event.action, value: authentication_failure}
                """);
        RuleDecl d = new RuleConfigLoader().loadDir(temp.toString()).get(0);
        WindowRule wr = new RuleBuilder().toWindowRule(d);
        assertEquals(null, wr.getSlidingMinutes(), "缺省 slidingMinutes 应为 null(tumbling)");
    }

    @Test
    void cep_and_baseline_declParse() throws IOException {
        write("rule-cep.yaml", """
                id: rule-cep
                name: CEP
                category: cep
                type: ssh_cep
                enabled: true
                severity: critical
                riskScore: 90
                keyField: source.ip
                cep:
                  withinMinutes: 10
                  pattern:
                    - {name: failures, type: begin, timesMin: 5, timesMax: 100, condition: {type: field_equals, field: event.action, value: authentication_failure}}
                    - {name: success, type: next, condition: {type: field_equals, field: event.action, value: authentication_success}}
                """);
        write("rule-base.yaml", """
                id: rule-base
                name: 基线
                category: baseline
                type: auth_anomaly
                enabled: true
                severity: high
                riskScore: 60
                baseline:
                  keyField: host.name
                  windowHours: 1
                  baselineHours: 24
                  minBaselineHours: 3
                """);
        RuleConfigLoader loader = new RuleConfigLoader();
        List<RuleDecl> decls = loader.loadDir(temp.toString());
        assertEquals(2, decls.size());
        RuleDecl cep = decls.stream().filter(d -> "rule-cep".equals(d.id)).findFirst().orElseThrow();
        assertEquals("cep", cep.category);
        assertEquals(10, cep.cep.withinMinutes);
        assertEquals(2, cep.cep.pattern.size());
        assertEquals("failures", cep.cep.pattern.get(0).name);
        assertEquals(5, cep.cep.pattern.get(0).timesMin);
        assertEquals(100, cep.cep.pattern.get(0).timesMax);
        RuleDecl base = decls.stream().filter(d -> "rule-base".equals(d.id)).findFirst().orElseThrow();
        assertEquals(24, base.baseline.baselineHours);
        assertEquals(3, base.baseline.minBaselineHours);
        assertEquals("host.name", base.baseline.keyField);
    }

    private static RuleDecl.ConditionSpec spec(String type, String field, Object value,
                                               List<Object> values, List<RuleDecl.ConditionSpec> conditions) {
        RuleDecl.ConditionSpec s = new RuleDecl.ConditionSpec();
        s.type = type;
        s.field = field;
        s.value = value;
        s.values = values;
        s.conditions = conditions;
        return s;
    }
}
