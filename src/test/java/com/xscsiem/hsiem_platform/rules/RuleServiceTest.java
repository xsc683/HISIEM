package com.xscsiem.hsiem_platform.rules;

import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 检测规则管理(story-03):读取 infra/rules/*.yaml、启停写回、MITRE 覆盖聚合。 */
class RuleServiceTest {

    @TempDir
    Path temp;

    private RuleService svc() {
        return new RuleService(temp.toString(), "http://localhost:9200");
    }

    private void writeRule(String id, boolean enabled) throws Exception {
        Files.writeString(temp.resolve(id + ".yaml"),
                "id: " + id + "\n"
                        + "name: 测试规则\n"
                        + "category: single_event\n"
                        + "type: ssh_auth\n"
                        + "enabled: " + enabled + "\n"
                        + "severity: medium\n"
                        + "description: desc\n"
                        + "riskScore: 40\n"
                        + "tags: [attack.t1110.001]\n"
                        + "status: experimental\n"
                        + "version: \"1.0\"\n"
                        + "condition:\n"
                        + "  type: field_equals\n"
                        + "  field: event.action\n"
                        + "  value: authentication_failure\n");
    }

    @Test
    void list_parsesRulesFromYaml() throws Exception {
        writeRule("rule-a", true);
        List<Map<String, Object>> rules = svc().list();
        assertEquals(1, rules.size());
        Map<String, Object> rule = rules.get(0);
        assertEquals("rule-a", rule.get("id"));
        assertEquals(true, rule.get("enabled"));
        assertEquals("single_event", rule.get("category"));
        assertEquals(40, rule.get("riskScore"));
        assertEquals(List.of("attack.t1110.001"), rule.get("tags"));
    }

    @Test
    void toggle_flipsEnabled_andWritesBack() throws Exception {
        writeRule("rule-a", true);
        RuleService svc = svc();
        Map<String, Object> toggled = svc.toggle("rule-a");
        assertFalse((Boolean) toggled.get("enabled"));
        // 重新加载确认已写回文件
        assertEquals(false, svc.get("rule-a").get("enabled"));
        // 再 toggle 恢复
        assertTrue((Boolean) svc.toggle("rule-a").get("enabled"));
    }

    @Test
    void toggle_changesOnlyEnabledLine_andPreservesComments() throws Exception {
        writeRule("rule-a", true);
        Path file = temp.resolve("rule-a.yaml");
        String original = Files.readString(file);
        String withComment = "# keep this review context\n" + original;
        Files.writeString(file, withComment);

        svc().toggle("rule-a");

        String updated = Files.readString(file);
        assertTrue(updated.startsWith("# keep this review context\n"));
        assertTrue(updated.contains("enabled: false"));
        assertFalse(updated.startsWith("---"));
        assertEquals(withComment.replace("enabled: true", "enabled: false"), updated);
    }

    @Test
    void toggle_missingRule_throws404() throws Exception {
        assertThrows(NotFoundException.class, () -> svc().toggle("nope"));
    }

    @Test
    void mitre_aggregatesTagsFromRules() throws Exception {
        writeRule("rule-a", true);
        Map<String, Object> mitre = svc().mitre();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coverage = (List<Map<String, Object>>) mitre.get("coverage");
        assertEquals(1, coverage.size());
        assertEquals("attack.t1110.001", coverage.get(0).get("technique"));
        assertEquals("rule-a", coverage.get(0).get("ruleId"));
        assertEquals("Detected", coverage.get(0).get("coverage"));
    }

    @Test
    void get_missingRule_throws404() throws Exception {
        assertThrows(NotFoundException.class, () -> svc().get("nope"));
    }

    @Test
    void create_writesValidatedSingleEventRule() throws Exception {
        Map<String, Object> payload = singleEvent("rule-ui-created");
        payload.put("name", "  UI 创建规则  ");
        payload.put("description", "  从可视化表单创建  ");
        Map<String, Object> created = svc().create(payload, "admin");

        assertEquals("rule-ui-created", created.get("id"));
        assertEquals("UI 创建规则", created.get("name"));
        assertEquals("从可视化表单创建", created.get("description"));
        assertEquals("field_equals", ((Map<?, ?>) created.get("condition")).get("type"));
        assertTrue(Files.readString(temp.resolve("rule-ui-created.yaml")).contains("event.action"));
        assertEquals(created.get("name"), svc().get("rule-ui-created").get("name"));
    }

    @Test
    void create_duplicateId_returnsConflict() throws Exception {
        svc().create(singleEvent("rule-duplicate"), "admin");

        assertThrows(com.xscsiem.hsiem_platform.onboarding.ConflictException.class,
                () -> svc().create(singleEvent("rule-duplicate"), "admin"));
    }

    @Test
    void update_windowRulePersistsWindowSemantics() throws Exception {
        Map<String, Object> original = singleEvent("rule-window-ui");
        svc().create(original, "admin");
        Map<String, Object> changed = new LinkedHashMap<>(original);
        changed.put("category", "window");
        changed.put("keyField", "source.ip");
        changed.put("windowMinutes", 5);
        changed.put("threshold", 7);
        changed.put("slidingMinutes", null);
        changed.put("alertSuppressionMinutes", null);

        Map<String, Object> updated = svc().update("rule-window-ui", changed, "admin");

        assertEquals(5L, updated.get("windowMinutes"));
        assertEquals(7, updated.get("threshold"));
        assertFalse(updated.containsKey("slidingMinutes"));
        assertFalse(updated.containsKey("alertSuppressionMinutes"));
        assertFalse(Files.readString(temp.resolve("rule-window-ui.yaml")).contains("null"));
        assertEquals("source.ip", svc().get("rule-window-ui").get("keyField"));
    }

    @Test
    void update_invalidDsl_keepsPreviousYaml() throws Exception {
        Map<String, Object> original = singleEvent("rule-safe-update");
        svc().create(original, "admin");
        Path path = temp.resolve("rule-safe-update.yaml");
        String before = Files.readString(path);
        Map<String, Object> invalid = new LinkedHashMap<>(original);
        invalid.put("condition", Map.of("type", "field_in", "field", "user.name", "values", List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> svc().update("rule-safe-update", invalid, "admin"));
        assertEquals(before, Files.readString(path));
    }

    @Test
    void create_rejectsPathTraversalAndUnsupportedCategory() {
        Map<String, Object> traversal = singleEvent("rule-safe-id");
        traversal.put("id", "../outside");
        assertThrows(IllegalArgumentException.class, () -> svc().create(traversal, "admin"));

        Map<String, Object> cep = singleEvent("rule-ui-cep");
        cep.put("category", "cep");
        assertThrows(IllegalArgumentException.class, () -> svc().create(cep, "admin"));
    }

    private static Map<String, Object> singleEvent(String id) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id);
        rule.put("name", "UI 创建规则");
        rule.put("category", "single_event");
        rule.put("type", "ui_detection");
        rule.put("enabled", true);
        rule.put("severity", "high");
        rule.put("description", "从可视化表单创建");
        rule.put("riskScore", 70);
        rule.put("tags", List.of("attack.t1110.001"));
        rule.put("status", "experimental");
        rule.put("version", "1.0");
        rule.put("condition", Map.of(
                "type", "field_equals",
                "field", "event.action",
                "value", "authentication_failure"));
        return rule;
    }
}
