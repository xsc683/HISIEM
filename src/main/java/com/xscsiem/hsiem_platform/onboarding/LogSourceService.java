package com.xscsiem.hsiem_platform.onboarding;

import com.xscsiem.hsiem_platform.control.ControlPlaneStore;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ControlPlaneStore control;

    @Autowired
    public LogSourceService(LogSourceStore store, ParserTemplateService templates,
                            ActivationCoordinator coordinator, ControlPlaneStore control) {
        this.store = store;
        this.templates = templates;
        this.coordinator = coordinator;
        this.control = control;
    }

    /** 轻量构造器仅供不启动 Spring/数据库的单元测试使用。 */
    public LogSourceService(LogSourceStore store, ParserTemplateService templates,
                            ActivationCoordinator coordinator) {
        this(store, templates, coordinator, null);
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
        String taskId = control == null ? null
                : control.createTask("log_source_activate", id, "等待数据源配置生效");
        s.taskId = taskId;
        s.lastError = null;
        store.save(s);
        ACTIVATOR.execute(() -> {
            if (taskId != null) {
                control.updateTask(taskId, "running", 10, "正在生成 Logstash 配置", null);
            }
            if (taskId != null) control.updateTask(taskId, "running", 40, "正在校验并同步 Logstash 配置", null);
            LogSource result = activateSync(id);
            if (taskId != null) {
                boolean success = "active".equals(result.status);
                control.updateTask(taskId, success ? "succeeded" : "failed", success ? 100 : 100,
                        success ? "数据源已生效" : "数据源生效失败", success ? null : result.lastError);
            }
        });
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
            s.lastError = e.getMessage();
            System.err.println("[LogSourceService] 数据源 " + id + " 生效失败: " + e.getMessage());
        } finally {
            s.updatedAt = Instant.now().toString();
            store.save(s);
        }
        return s;
    }

    public void delete(String id) {
        LogSource s = store.find(id);
        if ("active".equals(s.status)) {
            coordinator.deactivate(s);
        }
        store.delete(id);
    }

    /** 异步停用但保留声明，失败可重试，成功后状态为 stopped。 */
    public LogSource deactivateAsync(String id) {
        LogSource s = store.find(id);
        if (!"active".equals(s.status)) return s;
        String taskId = control == null ? null
                : control.createTask("log_source_deactivate", id, "等待数据源停用");
        s.taskId = taskId;
        s.lastError = null;
        store.save(s);
        ACTIVATOR.execute(() -> {
            if (taskId != null) control.updateTask(taskId, "running", 20, "正在移除 Logstash pipeline", null);
            try {
                coordinator.deactivate(s);
                s.status = "stopped";
                s.updatedAt = Instant.now().toString();
                store.save(s);
                if (taskId != null) control.updateTask(taskId, "succeeded", 100, "数据源已停用", null);
            } catch (Exception e) {
                s.lastError = e.getMessage();
                s.updatedAt = Instant.now().toString();
                store.save(s);
                if (taskId != null) control.updateTask(taskId, "failed", 100, "数据源停用失败", e.getMessage());
            }
        });
        return s;
    }
}
