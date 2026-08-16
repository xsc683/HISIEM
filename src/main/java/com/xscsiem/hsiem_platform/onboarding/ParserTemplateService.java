package com.xscsiem.hsiem_platform.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 加载 infra/parser-templates/*.yaml 为解析模板;支持保存(Story 02,正负样本门禁)。
 */
@Service
public class ParserTemplateService {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String templatesDir;
    private final GrokTestService grok;

    public ParserTemplateService(
            @Value("${app.parser-templates-dir:infra/parser-templates}") String templatesDir,
            GrokTestService grok) {
        this.templatesDir = templatesDir;
        this.grok = grok;
    }

    public List<ParserTemplate> list() {
        File dir = new File(templatesDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yaml") || name.endsWith(".yml"));
        List<ParserTemplate> result = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                try {
                    result.add(yamlMapper.readValue(f, ParserTemplate.class));
                } catch (Exception e) {
                    throw new IllegalStateException("解析模板加载失败: " + f.getName(), e);
                }
            }
        }
        return result;
    }

    public ParserTemplate find(String id) {
        return list().stream()
                .filter(t -> t.id != null && t.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("模板不存在: " + id));
    }

    /**
     * 保存模板(Story 02 FR-4,正负样本门禁):写 infra/parser-templates/&lt;id&gt;.yaml。
     * 门禁:至少 1 个 grok 模式 + ≥1 正样本(全部命中且 expect 字段匹配)+ 负样本(如有)全部不命中。
     * 校验通过才允许保存;失败抛 IllegalArgumentException(400)。
     */
    public ParserTemplate save(ParserTemplate t) {
        validateGate(t);
        File f = new File(templatesDir, t.id + ".yaml");
        try {
            yamlMapper.writeValue(f, t);
        } catch (IOException e) {
            throw new IllegalStateException("模板保存失败: " + t.id, e);
        }
        return t;
    }

    /** 正负样本门禁(先于保存)。 */
    public void validateGate(ParserTemplate t) {
        if (t.id == null || t.id.isBlank()) {
            throw new IllegalArgumentException("模板 id 不能为空");
        }
        if (t.patterns == null || t.patterns.isEmpty()) {
            throw new IllegalArgumentException("模板至少需要一个 grok 模式");
        }
        if (t.tests == null || t.tests.isEmpty()) {
            throw new IllegalArgumentException("模板至少需要一个正样本(请先验证样例日志)");
        }
        for (ParserTemplate.Test test : t.tests) {
            GrokTestService.ParseResult r = grok.test(t, test.sample);
            if (!r.ok()) {
                throw new IllegalArgumentException("正样本未命中任何 grok 模式: " + test.sample);
            }
            if (test.expect != null) {
                for (var entry : test.expect.entrySet()) {
                    Object actual = r.fields().get(entry.getKey());
                    if (!Objects.equals(String.valueOf(actual), String.valueOf(entry.getValue()))) {
                        throw new IllegalArgumentException("正样本字段不符: " + entry.getKey()
                                + "=" + actual + "(期望 " + entry.getValue() + "),样例: " + test.sample);
                    }
                }
            }
        }
        if (t.negative != null) {
            for (String neg : t.negative) {
                GrokTestService.ParseResult r = grok.test(t, neg);
                if (r.ok()) {
                    throw new IllegalArgumentException("负样本不应命中任何 grok 模式: " + neg);
                }
            }
        }
    }
}
