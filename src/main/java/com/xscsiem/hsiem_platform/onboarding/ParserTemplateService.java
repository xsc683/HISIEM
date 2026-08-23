package com.xscsiem.hsiem_platform.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xscsiem.hsiem_platform.control.ConfigRevisionJournal;
import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 加载 infra/parser-templates/*.yaml 为解析模板;支持保存(Story 02,正负样本门禁)。
 */
@Service
public class ParserTemplateService {

    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String templatesDir;
    private final GrokTestService grok;
    private final ControlPlaneStore control;

    @Autowired
    public ParserTemplateService(
            @Value("${app.parser-templates-dir:infra/parser-templates}") String templatesDir,
            GrokTestService grok, ControlPlaneStore control) {
        this.templatesDir = templatesDir;
        this.grok = grok;
        this.control = control;
    }

    public ParserTemplateService(String templatesDir, GrokTestService grok) {
        this.templatesDir = templatesDir;
        this.grok = grok;
        this.control = null;
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
            ConfigRevisionJournal.atomicWrite(f.toPath(), yamlMapper.writeValueAsString(t));
            ConfigRevisionJournal.record(control, "parser-template", f.toPath(), actor());
        } catch (IOException e) {
            throw new IllegalStateException("模板保存失败: " + t.id, e);
        }
        return t;
    }

    private static String actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    /** 正负样本门禁(先于保存)。 */
    public void validateGate(ParserTemplate t) {
        validateDefinition(t, true);
        if (t.tests == null || t.tests.isEmpty()) {
            throw new IllegalArgumentException("模板至少需要一个正样本(请先验证样例日志)");
        }
        for (ParserTemplate.Test test : t.tests) {
            if (test == null || test.sample == null || test.sample.isBlank()) {
                throw new IllegalArgumentException("正样本不能为空");
            }
            SampleSizeValidator.requireApiSample(test.sample);
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
                SampleSizeValidator.requireApiSample(neg);
                GrokTestService.ParseResult r = grok.test(t, neg);
                if (r.ok()) {
                    throw new IllegalArgumentException("负样本不应命中任何 grok 模式: " + neg);
                }
            }
        }
    }

    /** 校验可执行的模板定义；草稿测试时允许尚未填写 ID 和名称。 */
    public void validateDefinition(ParserTemplate t, boolean requireIdentity) {
        if (t == null) {
            throw new IllegalArgumentException("模板不能为空");
        }
        if (requireIdentity && (t.id == null || !SAFE_ID.matcher(t.id).matches())) {
            throw new IllegalArgumentException("模板 id 仅支持 1-64 位小写字母、数字、下划线和连字符，且必须以字母或数字开头");
        }
        if (requireIdentity && (t.name == null || t.name.isBlank())) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        if (t.patterns == null || t.patterns.isEmpty()
                || t.patterns.stream().anyMatch(pattern -> pattern == null || pattern.isBlank())) {
            throw new IllegalArgumentException("模板至少需要一个非空 grok 模式");
        }
    }
}
