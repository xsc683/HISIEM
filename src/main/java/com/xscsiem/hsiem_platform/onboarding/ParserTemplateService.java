package com.xscsiem.hsiem_platform.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 加载 infra/parser-templates/*.yaml 为解析模板。 */
@Service
public class ParserTemplateService {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final String templatesDir;

    public ParserTemplateService(
            @Value("${app.parser-templates-dir:infra/parser-templates}") String templatesDir) {
        this.templatesDir = templatesDir;
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
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + id));
    }
}
