package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户接入层 API(Phase 4,Story 01/02):解析模板、解析测试、数据源 CRUD/生效、模板保存。 */
@RestController
@RequestMapping("/api")
public class OnboardingController {

    private final ParserTemplateService templates;
    private final GrokTestService grok;
    private final LogstashConfigGenerator generator;
    private final LogSourceService logSources;

    public OnboardingController(ParserTemplateService templates, GrokTestService grok,
                                LogstashConfigGenerator generator, LogSourceService logSources) {
        this.templates = templates;
        this.grok = grok;
        this.generator = generator;
        this.logSources = logSources;
    }

    /** 模板列表(前端"选模板"用)。 */
    @GetMapping("/parser-templates")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public List<ParserTemplate> listTemplates() {
        return templates.list();
    }

    /** 解析测试:用模板解析样例日志,返回提取字段。 */
    @PostMapping("/parser-templates/test")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS')")
    public Map<String, Object> test(@RequestBody TestRequest req) {
        SampleSizeValidator.requireApiSample(req.sample());
        ParserTemplate template = templates.find(req.templateId());
        GrokTestService.ParseResult result = grok.test(template, req.sample());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", result.ok());
        resp.put("fields", result.fields());
        return resp;
    }

    /** 数据源接入预览:由模板生成 Logstash 配置片段。 */
    @PostMapping("/log-sources/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS')")
    public Map<String, Object> preview(@RequestBody LogSourceRequest req) {
        logSources.validate(req.name(), req.protocol(), req.templateId(), req.port(), req.path());
        ParserTemplate template = templates.find(req.templateId());
        LogSource preview = LogSource.creating(req.name(), req.protocol(), req.templateId(), req.port(), req.path());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("template", template.id);
        resp.put("input", generator.generateInput(preview));
        resp.put("config", generator.generateFilter(template));
        return resp;
    }

    /** 保存解析模板(Story 02 FR-4,正负样本门禁)。 */
    @PostMapping("/parser-templates")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
    @ResponseStatus(HttpStatus.CREATED)
    public ParserTemplate saveTemplate(@RequestBody ParserTemplate template) {
        return templates.save(template);
    }

    /** 数据源列表(Story 01 FR-4)。 */
    @GetMapping("/log-sources")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public List<LogSource> listSources() {
        return logSources.list();
    }

    /** 数据源详情(前端轮询状态用)。 */
    @GetMapping("/log-sources/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'OPS', 'AUDIT')")
    public LogSource getSource(@PathVariable String id) {
        return logSources.get(id);
    }

    /** 创建数据源(落库 creating;模板不存在 404、端口冲突 409)。 */
    @PostMapping("/log-sources")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
    @ResponseStatus(HttpStatus.CREATED)
    public LogSource createSource(@RequestBody CreateSourceRequest req) {
        return logSources.create(req.name(), req.protocol(), req.templateId(), req.port(), req.path());
    }

    /** 生效(Story 01 FR-3):异步执行,202 返回当前状态,前端轮询详情直至 active/failed。 */
    @PostMapping("/log-sources/{id}/activate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
    public ResponseEntity<LogSource> activateSource(@PathVariable String id) {
        LogSource s = logSources.activateAsync(id);
        return ResponseEntity.accepted().body(s);
    }

    @PostMapping("/log-sources/{id}/deactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
    public ResponseEntity<LogSource> deactivateSource(@PathVariable String id) {
        return ResponseEntity.accepted().body(logSources.deactivateAsync(id));
    }

    /** 删除数据源(文件移除;Logstash input 清理列 P1)。 */
    @DeleteMapping("/log-sources/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSource(@PathVariable String id) {
        logSources.delete(id);
    }

    public record TestRequest(String templateId, String sample) {
    }

    public record LogSourceRequest(String name, String protocol, String templateId, int port, String path) {
        public LogSourceRequest(String name, String protocol, String templateId, int port) {
            this(name, protocol, templateId, port, null);
        }
    }

    public record CreateSourceRequest(String name, String protocol, String templateId, int port, String path) {
        public CreateSourceRequest(String name, String protocol, String templateId, int port) {
            this(name, protocol, templateId, port, null);
        }
    }
}
