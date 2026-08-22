package com.xscsiem.hsiem_platform.onboarding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Story 01:完整 pipeline 生成(input + filter + output + source 标识)。 */
class LogstashConfigGeneratorTest {

    private final LogstashConfigGenerator generator = new LogstashConfigGenerator();

    private ParserTemplate sshTemplate() {
        ParserTemplate t = new ParserTemplate();
        t.id = "ssh-auth";
        t.name = "SSH 认证日志";
        t.patterns = List.of(
                "%{SYSLOGTIMESTAMP:timestamp} %{HOSTNAME:host.name} sshd.*Failed password for %{USERNAME:user.name} from %{IP:source.ip}");
        ParserTemplate.Timestamp ts = new ParserTemplate.Timestamp();
        ts.source = "timestamp";
        ts.formats = List.of("MMM dd HH:mm:ss", "MMM  d HH:mm:ss");
        ts.timezone = "Asia/Shanghai";
        t.timestamp = ts;
        t.ecs = Map.of("event.category", "authentication");
        return t;
    }

    private LogSource source() {
        LogSource s = LogSource.creating("ssh-web-01", "tcp", "ssh-auth", 5001);
        s.id = "ls-abc12345";
        s.sourceId = "ls-abc12345";
        return s;
    }

    @Test
    void pipeline_contains_input_sourceMarkers_filter_output() {
        String c = generator.generatePipeline(source(), sshTemplate());
        assertTrue(c.contains("port => 5001"), "input 端口");
        assertTrue(c.contains("log.source_id"), "注入 source_id 字段");
        assertTrue(c.contains("ls-abc12345"), "source_id 值");
        assertTrue(c.contains("log.source_name"), "注入 source_name 字段");
        assertTrue(c.contains("ssh-web-01"), "source_name 值");
        assertTrue(c.contains("grok {"), "filter grok");
        assertTrue(c.contains("event.category"), "ECS 字段");
        assertTrue(c.contains("_dateparsefailure"), "非法时间戳应进入 raw 分流");
        assertTrue(c.contains("or \"_dateparsefailure\" in [tags]"), "时间解析失败不能进入 Kafka/Flink");
        assertTrue(c.contains("related.ip"), "规范化字段");
        assertTrue(c.contains("siem-events-%{+YYYY.MM.dd}"), "ES 输出");
        assertTrue(c.contains("topic_id => \"siem-events\""), "Kafka 输出");
        assertFalse(c.contains("ls-ls-"), "不应出现 ls-ls- 重复前缀");
        // 回归:Logstash 8.14 数组不接受尾逗号(date match 曾在末尾留 ", ]" 导致校验 FATAL)
        assertFalse(c.contains("\", ]"), "数组不应有尾逗号");
        assertTrue(c.contains("\"MMM  d HH:mm:ss\" ]"), "date match 末项后应直接闭括号(无尾逗号)");
    }

    @Test
    void syslogAndFileInputsUseProtocolSpecificConfiguration() {
        LogSource syslog = LogSource.creating("syslog", "syslog", "ssh-auth", 5514);
        String syslogConfig = generator.generatePipeline(syslog, sshTemplate());
        assertTrue(syslogConfig.contains("syslog {"));
        assertTrue(syslogConfig.contains("port => 5514"));

        LogSource file = LogSource.creating("auth-file", "file", "ssh-auth", 0, "/var/log/auth.log");
        String fileConfig = generator.generatePipeline(file, sshTemplate());
        assertTrue(fileConfig.contains("file {"));
        assertTrue(fileConfig.contains("path => [\"/var/log/auth.log\"]"));
        assertTrue(fileConfig.contains("sincedb_path => \"/usr/share/logstash/data/sincedb-" + file.id + "\""));
    }
}
