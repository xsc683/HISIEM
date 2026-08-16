package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 由解析模板生成 Logstash 配置:
 * - generateFilter:filter 片段(数据源接入预览用,与 infra/logstash/pipeline 写法一致)。
 * - generatePipeline:每数据源自包含的完整 pipeline(input + filter + output,Story 01 生效用)。
 *   生成片段 = 生产 pipeline 子集:remove_field timestamp、pipeline/schema_version/related.ip、geoip
 *   在主 pipeline 兜底处由本生成器一并补全(见 06 §5.2)。
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

    /**
     * 生成每数据源自包含的完整 Logstash pipeline(input + filter + output)。
     * - input:tcp 监听该源端口,add_field 注入 log.source_id / log.source_name(story-05 聚合维度)。
     * - filter:模板 grok/date/ecs/actions + 规范化(remove timestamp、pipeline、schema_version、related.ip)+ geoip。
     * - output:与主 pipeline 一致(ES siem-events-* + Kafka siem-events,进入检测链)。
     * 该片段写入 infra/logstash/pipeline/log-sources/&lt;id&gt;.conf 并在 pipelines.yml 注册为独立 pipeline。
     */
    public String generatePipeline(LogSource s, ParserTemplate t) {
        StringBuilder sb = new StringBuilder();
        // input:注入数据源标识(story-01 FR-6 / story-05 聚合)
        sb.append("input {\n");
        sb.append("  tcp {\n");
        sb.append("    port => ").append(s.port).append("\n");
        sb.append("    add_field => { \"log.source_id\" => \"").append(s.sourceId).append("\" ")
                .append("\"log.source_name\" => \"").append(escape(s.name)).append("\" }\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        // filter:模板片段(缩进 2 格) + 规范化 + geoip
        sb.append("filter {\n");
        sb.append(indent(generateFilter(t), 2));
        sb.append("\n  # 字段规范化(ECS 对齐,决策 D:扁平存储)\n");
        sb.append("  mutate {\n");
        sb.append("    remove_field => [ \"timestamp\" ]\n");
        sb.append("    add_field => {\n");
        sb.append("      \"pipeline\" => \"mini-siem\"\n");
        sb.append("      \"event.schema_version\" => \"1.0\"\n");
        sb.append("    }\n");
        sb.append("    add_field => { \"related.ip\" => \"%{source.ip}\" }\n");
        sb.append("  }\n");
        sb.append("  if [source.ip] {\n");
        sb.append("    geoip {\n");
        sb.append("      source => \"source.ip\"\n");
        sb.append("      target => \"source\"\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        // output:与主 pipeline 一致
        sb.append("output {\n");
        sb.append("  elasticsearch {\n");
        sb.append("    hosts => [\"http://elasticsearch:9200\"]\n");
        sb.append("    index => \"siem-events-%{+YYYY.MM.dd}\"\n");
        sb.append("  }\n\n");
        sb.append("  kafka {\n");
        sb.append("    bootstrap_servers => \"kafka:9092\"\n");
        sb.append("    topic_id => \"siem-events\"\n");
        sb.append("    codec => json\n");
        sb.append("    acks => \"all\"\n");
        sb.append("    retries => 5\n");
        sb.append("    retry_backoff_ms => 1000\n");
        sb.append("    compression_type => \"zstd\"\n");
        sb.append("    batch_size => 131072\n");
        sb.append("    linger_ms => 5\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** 每行缩进 n 个空格(用于把 filter 片段嵌入 filter {} 块)。空行不缩进。 */
    private static String indent(String s, int n) {
        String pad = "  ".repeat(n);
        return Arrays.stream(s.split("\n", -1))
                .map(line -> line.isEmpty() ? line : pad + line)
                .collect(Collectors.joining("\n"));
    }

    /** Logstash 配置字符串转义(名称含引号/换行时防注入)。 */
    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
