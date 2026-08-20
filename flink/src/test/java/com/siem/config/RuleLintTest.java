package com.siem.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则 lint(检测即代码门禁):加载 infra/rules/*.yaml 并校验 schema,
 * 作为 CI 门禁的一部分(新增/修改规则必须通过)。
 * 校验项:文件可解析、id 唯一、category 枚举、type/severity 非空、
 * riskScore 0-100、MITRE tag 格式(attack.tNNNN[.NN])、status 枚举、
 * 各类型必备参数(condition/keyField/windowMinutes/threshold/cep/baseline)。
 */
class RuleLintTest {

    private static final List<String> CATEGORIES = List.of("single_event", "window", "cep", "baseline");
    private static final List<String> STATUSES = List.of("experimental", "stable", "deprecated");
    private static final String MITRE_TAG = "attack\\.t\\d+(\\.\\d+)?";

    @Test
    void allRulesPassLint() throws Exception {
        // 从 flink 模块跑时是 ../infra/rules;从仓库根跑时是 infra/rules
        String dir = "../infra/rules";
        if (!Files.isDirectory(Path.of(dir))) {
            dir = "infra/rules";
        }
        Assumptions.assumeTrue(Files.isDirectory(Path.of(dir)),
                "infra/rules 不存在,跳过 lint(仅在仓库内有效)");

        List<RuleDecl> decls = new RuleConfigLoader().loadDir(dir);
        assertFalse(decls.isEmpty(), "infra/rules 不应为空");
        assertEquals(6, decls.size(), "规则数量应等于已发布 6 条");

        Set<String> ids = new HashSet<>();
        for (RuleDecl d : decls) {
            assertTrue(ids.add(d.id), "规则 id 重复: " + d.id);
            assertTrue(CATEGORIES.contains(d.category), d.id + " category 非法: " + d.category);
            assertNotNull(d.type, d.id + " 缺 type");
            assertNotNull(d.severity, d.id + " 缺 severity");
            if (d.riskScore != null) {
                assertTrue(d.riskScore >= 0 && d.riskScore <= 100, d.id + " riskScore 越界: " + d.riskScore);
            }
            if (d.tags != null) {
                for (String tag : d.tags) {
                    assertTrue(tag.matches(MITRE_TAG), d.id + " MITRE tag 格式非法: " + tag);
                }
            }
            if (d.status != null) {
                assertTrue(STATUSES.contains(d.status), d.id + " status 非法: " + d.status);
            }
            if (d.references != null) {
                // 每条 references 应为 URL 或 "framework:id" 形式(如 "MITRE:https://attack.mitre.org/techniques/T1110/")
                for (String ref : d.references) {
                    assertTrue(ref != null && !ref.isBlank(), d.id + " references 含空项");
                    assertTrue(ref.matches("(?i)^https?://.*") || ref.matches("^[A-Za-z0-9._-]+:.*"),
                            d.id + " references 格式非法: " + ref);
                }
            }
            switch (d.category) {
                case "single_event" -> assertNotNull(d.condition, d.id + " single_event 缺 condition");
                case "window" -> {
                    assertNotNull(d.condition, d.id + " window 缺 condition");
                    assertNotNull(d.keyField, d.id + " window 缺 keyField");
                    assertNotNull(d.windowMinutes, d.id + " window 缺 windowMinutes");
                    assertNotNull(d.threshold, d.id + " window 缺 threshold");
                    if (d.slidingMinutes != null) {
                        assertTrue(d.slidingMinutes > 0, d.id + " slidingMinutes 应 > 0");
                        assertTrue(d.slidingMinutes <= d.windowMinutes,
                                d.id + " slidingMinutes 不应大于 windowMinutes");
                    }
                    if (d.alertSuppressionMinutes != null) {
                        assertTrue(d.alertSuppressionMinutes > 0,
                                d.id + " alertSuppressionMinutes 应 > 0");
                    }
                }
                case "cep" -> {
                    assertNotNull(d.keyField, d.id + " cep 缺 keyField");
                    assertNotNull(d.cep, d.id + " cep 缺 cep");
                    assertNotNull(d.cep.pattern, d.id + " cep 缺 pattern");
                    assertFalse(d.cep.pattern.isEmpty(), d.id + " cep pattern 为空");
                }
                case "baseline" -> {
                    assertNotNull(d.baseline, d.id + " baseline 缺 baseline");
                    assertNotNull(d.baseline.baselineHours, d.id + " baseline 缺 baselineHours");
                    assertNotNull(d.baseline.minBaselineHours, d.id + " baseline 缺 minBaselineHours");
                }
                default -> { /* category 已在上面校验 */ }
            }
        }
    }
}
