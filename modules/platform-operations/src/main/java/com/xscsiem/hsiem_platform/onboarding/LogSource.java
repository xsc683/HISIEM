package com.xscsiem.hsiem_platform.onboarding;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.time.Instant;
import java.util.UUID;

/**
 * 数据源声明(用户接入层 Phase 4,Story 01)。
 * 对应 infra/log-sources/*.yaml(文件 + Git,决策:文件 + Git,见 story-01 ADR-1)。
 *
 * 状态机:creating → active(生效成功:配置校验 + 同步 + Logstash 重启)
 *               → failed(校验/同步/重启失败,可重试)
 *               → stopped(停用,移除 input;P1)
 * 状态取值与 story-01 §4.3 枚举字典一致(英文值,UI 展示可中文)。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class LogSource {

    /** 内部 id(如 ls-a1b2c3d4),同时作为配置文件名的 slug。 */
    public String id;
    /** 显示名(如 ssh-auth-web-01)。 */
    public String name;
    /** 采集协议:tcp / syslog / file(当前实现 tcp,见 06 §3.1 能力边界)。 */
    public String protocol;
    /** 引用的解析模板 id(infra/parser-templates/*.yaml)。 */
    public String templateId;
    /** 采集端口(tcp/syslog 协议,1-65535;file 协议为 0)。 */
    public int port;
    /** file 协议的输入路径；tcp/syslog 为空。 */
    public String path;
    /** 状态机:creating / active / stopped / failed。 */
    public String status;
    /** log.source_id 值:随生成配置 add_field 注入事件,是 story-05 按源聚合的维度。 */
    public String sourceId;
    public String createdAt;
    public String updatedAt;
    /** 最近一次异步生命周期任务,供前端轮询 /api/tasks/{id}。 */
    public String taskId;
    public String lastError;

    public LogSource() {
    }

    /** 新建(creating 状态)。sourceId 用内部 id,稳定且与配置文件一一对应。 */
    public static LogSource creating(String name, String protocol, String templateId, int port) {
        return creating(name, protocol, templateId, port, null);
    }

    public static LogSource creating(String name, String protocol, String templateId, int port, String path) {
        LogSource s = new LogSource();
        s.id = "ls-" + UUID.randomUUID().toString().substring(0, 8);
        s.name = name;
        s.protocol = protocol;
        s.templateId = templateId;
        s.port = port;
        s.path = path;
        s.status = "creating";
        s.sourceId = s.id;
        String now = Instant.now().toString();
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }
}
