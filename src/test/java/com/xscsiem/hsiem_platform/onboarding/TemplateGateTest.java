package com.xscsiem.hsiem_platform.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.fail;

/** story-09:加载 infra/parser-templates/*.yaml,正负样本门禁须全过。 */
class TemplateGateTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final GrokTestService grok = new GrokTestService();
    private final ParserTemplateService svc = new ParserTemplateService("infra/parser-templates", grok);

    @Test
    void allPresetTemplatesPassGate() throws Exception {
        File dir = new File("infra/parser-templates");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yaml"));
        if (files == null || files.length == 0) {
            fail("未找到模板目录 infra/parser-templates");
        }
        for (File f : files) {
            ParserTemplate t = yamlMapper.readValue(f, ParserTemplate.class);
            try {
                svc.validateGate(t);
                System.out.println("PASS: " + t.id);
            } catch (Exception e) {
                // 逐条打正负样本明细,便于定位哪个模板/哪条样例失败
                for (ParserTemplate.Test test : t.tests) {
                    GrokTestService.ParseResult r = grok.test(t, test.sample);
                    System.out.println("[diag] " + t.id + " positive ok=" + r.ok() + " fields=" + r.fields().keySet());
                }
                if (t.negative != null) {
                    for (String neg : t.negative) {
                        GrokTestService.ParseResult r = grok.test(t, neg);
                        System.out.println("[diag] " + t.id + " negative ok=" + r.ok() + " (应 false)");
                    }
                }
                fail("模板门禁失败: " + f.getName() + " → " + e.getMessage());
            }
        }
    }
}
