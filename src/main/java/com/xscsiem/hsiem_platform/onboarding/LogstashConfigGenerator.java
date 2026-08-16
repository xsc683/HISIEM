package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.stereotype.Component;

/**
 * 由解析模板生成 Logstash filter 片段(数据源接入预览用)。
 * 生成的片段与 infra/logstash/pipeline 的写法一致,可手动合入 pipeline。
 */
@Component
public class LogstashConfigGenerator {

    public String generateFilter(ParserTemplate t) {
        StringBuilder sb = new StringBuilder();

        if (t.patterns != null && !t.patterns.isEmpty()) {
            sb.append("# grok 解析(模板 ").append(t.id).append(")\n");
            sb.append("grok {\n  match => { \"message\" => [\n");
            for (int i = 0; i < t.patterns.size(); i++) {
                sb.append("    \"").append(t.patterns.get(i)).append("\"")
                        .append(i < t.patterns.size() - 1 ? "," : "").append("\n");
            }
            sb.append("  ] }\n  tag_on_failure => [\"_parsefailure\"]\n}\n");
        }

        if (t.timestamp != null) {
            sb.append("# @timestamp = 日志时间\n");
            sb.append("date {\n  match => [ \"").append(t.timestamp.source).append("\",");
            for (String f : t.timestamp.formats) {
                sb.append(" \"").append(f).append("\",");
            }
            sb.append(" ]\n  timezone => \"").append(t.timestamp.timezone).append("\"\n  target => \"@timestamp\"\n}\n");
        }

        if (t.ecs != null && !t.ecs.isEmpty()) {
            sb.append("mutate {\n  add_field => {");
            t.ecs.forEach((k, v) -> sb.append(" \"").append(k).append("\" => \"").append(v).append("\""));
            sb.append(" }\n}\n");
        }

        if (t.actions != null) {
            for (ParserTemplate.Action a : t.actions) {
                if (a.match == null || a.fields == null) {
                    continue;
                }
                sb.append("if [message] =~ ").append(a.match).append(" {\n  mutate { add_field => {");
                a.fields.forEach((k, v) -> sb.append(" \"").append(k).append("\" => \"").append(v).append("\""));
                sb.append(" } }\n}\n");
            }
        }
        return sb.toString();
    }
}
