package com.xscsiem.hsiem_platform.onboarding;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据源生命周期(Story 01):CRUD + 状态机(creating → active/failed → stopped)+ 生效。
 * 生效异步执行(后台线程),前端轮询 GET /api/log-sources/{id} 观察状态变化。
 */
@Service
public class LogSourceService {

    private static final ExecutorService ACTIVATOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "log-source-activate");
        t.setDaemon(true);
        return t;
    });

    private final LogSourceStore store;
    private final ParserTemplateService templates;
    private final ActivationCoordinator coordinator;

    public LogSourceService(LogSourceStore store, ParserTemplateService templates,
                            ActivationCoordinator coordinator) {
        this.store = store;
        this.templates = templates;
        this.coordinator = coordinator;
    }

    public List<LogSource> list() {
        return store.list();
    }

    public LogSource get(String id) {
        return store.find(id);
    }

    /** 创建(落库为 creating)。校验:模板存在(404)、协议 tcp、端口合法且未被占用(409)。 */
    public LogSource create(String name, String protocol, String templateId, int port) {
        templates.find(templateId); // 模板不存在 → 404
        if (protocol == null || protocol.isBlank()) {
            protocol = "tcp";
        }
        if (!"tcp".equals(protocol)) {
            throw new IllegalArgumentException("当前仅支持 tcp 协议(见 06 §3.1 能力边界)");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口需在 1-65535 之间");
        }
        boolean taken = store.list().stream().anyMatch(s -> s.port == port);
        if (taken) {
            throw new PortConflictException(port);
        }
        LogSource s = LogSource.creating(name, protocol, templateId, port);
        store.save(s);
        return s;
    }

    /** 异步激活:立即返回当前(creating)数据源,后台执行生效并更新状态;前端轮询状态。 */
    public LogSource activateAsync(String id) {
        LogSource s = store.find(id);
        if ("active".equals(s.status) || "stopped".equals(s.status)) {
            return s;
        }
        ACTIVATOR.execute(() -> activateSync(id));
        return s;
    }

    /** 同步激活(内部/测试用):成功 → active;失败 → failed(文件已回滚)。 */
    public LogSource activateSync(String id) {
        LogSource s = store.find(id);
        try {
            ParserTemplate t = templates.find(s.templateId);
            coordinator.activate(s, t);
            s.status = "active";
        } catch (Exception e) {
            s.status = "failed";
            System.err.println("[LogSourceService] 数据源 " + id + " 生效失败: " + e.getMessage());
        } finally {
            s.updatedAt = Instant.now().toString();
            store.save(s);
        }
        return s;
    }

    public void delete(String id) {
        store.find(id);
        // TODO(P1,story-01 FR):停用/删除需重新生成不含该 input 的配置并释放端口(重启 Logstash)
        store.delete(id);
    }
}
