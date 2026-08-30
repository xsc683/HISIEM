package com.xscsiem.hsiem_platform.onboarding;

import io.krakens.grok.api.Grok;
import io.krakens.grok.api.GrokCompiler;
import io.krakens.grok.api.Match;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析测试(parse-test):用模板的 grok 模式解析样例日志,返回提取字段。
 * 基于 java-grok(Logstash grok 的 Java 移植,自带默认模式如 SYSLOGTIMESTAMP/USERNAME/IP)。
 */
@Service
public class GrokTestService {

    private final GrokCompiler compiler;

    public GrokTestService() {
        compiler = GrokCompiler.newInstance();
        compiler.registerDefaultPatterns();
    }

    /** 结果:ok=是否解析成功;fields=提取 + 补的字段。 */
    public record ParseResult(boolean ok, Map<String, Object> fields) {
    }

    public ParseResult test(ParserTemplate template, String sample) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("message", sample);

        boolean matched = false;
        if (template.patterns != null) {
            for (String pattern : template.patterns) {
                Grok grok = compiler.compile(pattern);
                Match m = grok.match(sample);
                Map<String, Object> captures = m.capture();
                if (captures != null && !captures.isEmpty()) {
                    captures.forEach((k, v) -> fields.put(k, first(v)));
                    matched = true;
                    break;
                }
            }
        }
        if (!matched) {
            return new ParseResult(false, fields);
        }

        // 固定 ECS 字段
        if (template.ecs != null) {
            fields.putAll(template.ecs);
        }
        // 按消息内容补 event.action 等
        if (template.actions != null) {
            for (ParserTemplate.Action a : template.actions) {
                if (a.match != null && matches(a.match, sample)) {
                    fields.putAll(a.fields);
                    break;
                }
            }
        }
        return new ParseResult(true, fields);
    }

    /** java-grok 的 capture 值可能是 List<String>,取首个。 */
    private static Object first(Object v) {
        if (v instanceof List<?> list && !list.isEmpty()) {
            return list.get(0);
        }
        return v;
    }

    /** match 形如 "/Failed password/":去首尾斜杠后作为正则 find。 */
    private static boolean matches(String spec, String sample) {
        String regex = spec;
        if (spec.startsWith("/") && spec.endsWith("/") && spec.length() > 2) {
            regex = spec.substring(1, spec.length() - 1);
        }
        return Pattern.compile(regex).matcher(sample).find();
    }
}
