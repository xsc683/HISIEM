package com.xscsiem.hsiem_platform.rules;

import com.xscsiem.hsiem_platform.onboarding.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
