package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户接入层 API(Phase 4):解析模板、解析测试、数据源配置预览。 */
@RestController
@RequestMapping("/api")
public class OnboardingController {

    private final ParserTemplateService templates;
    private final GrokTestService grok;
    private final LogstashConfigGenerator generator;

    public OnboardingController(ParserTemplateService templates, GrokTestService grok,
                                LogstashConfigGenerator generator) {
        this.templates = templates;
        this.grok = grok;
        this.generator = generator;
    }

    /** 模板列表(前端"选模板"用)。 */
    @GetMapping("/parser-templates")
    public List<ParserTemplate> listTemplates() {
        return templates.list();
    }

    /** 解析测试:用模板解析样例日志,返回提取字段。 */
    @PostMapping("/parser-templates/test")
    public Map<String, Object> test(@RequestBody TestRequest req) {
        ParserTemplate template = templates.find(req.templateId());
        GrokTestService.ParseResult result = grok.test(template, req.sample());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", result.ok());
        resp.put("fields", result.fields());
        return resp;
    }

    /** 数据源接入预览:由模板生成 Logstash 配置片段。 */
    @PostMapping("/log-sources/preview")
    public Map<String, Object> preview(@RequestBody LogSourceRequest req) {
        ParserTemplate template = templates.find(req.templateId());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("template", template.id);
        resp.put("input", "tcp { port => " + req.port() + " }");
        resp.put("config", generator.generateFilter(template));
        return resp;
    }

    public record TestRequest(String templateId, String sample) {
    }

    public record LogSourceRequest(String name, String protocol, String templateId, int port) {
    }
}
