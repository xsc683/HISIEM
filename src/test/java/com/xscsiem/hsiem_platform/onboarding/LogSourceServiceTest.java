package com.xscsiem.hsiem_platform.onboarding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Story 01:数据源 CRUD + 状态机(创建/激活成功→active/激活失败→failed/端口冲突/模板缺失)。 */
class LogSourceServiceTest {

    @TempDir
    Path temp;

    private LogSourceStore store;
    private ParserTemplateService templates;
    private ActivationCoordinator coordinator;
    private LogSourceService service;

    @BeforeEach
    void setUp() {
        store = new LogSourceStore(temp.resolve("log-sources").toString());
        templates = mock(ParserTemplateService.class);
        coordinator = mock(ActivationCoordinator.class);
        service = new LogSourceService(store, templates, coordinator);
        when(templates.find("ssh-auth")).thenReturn(new ParserTemplate());
    }

    @Test
    void create_persistsCreatingState() {
        LogSource s = service.create("web-01", "tcp", "ssh-auth", 5001);
        assertEquals("creating", s.status);
        assertEquals(5001, s.port);
        assertEquals("web-01", s.name);
        assertNotNull(s.sourceId);
        assertEquals(1, store.list().size());
    }

    @Test
    void create_duplicatePort_conflict409() {
        service.create("web-01", "tcp", "ssh-auth", 5001);
        assertThrows(PortConflictException.class,
                () -> service.create("web-02", "tcp", "ssh-auth", 5001));
    }

    @Test
    void create_missingTemplate_notFound404() {
        when(templates.find("nope")).thenThrow(new NotFoundException("模板不存在: nope"));
        assertThrows(NotFoundException.class,
                () -> service.create("web-01", "tcp", "nope", 5001));
    }

    @Test
    void create_badPort_badRequest400() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("web-01", "tcp", "ssh-auth", 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("web-01", "tcp", "ssh-auth", 70000));
    }

    @Test
    void create_syslogProtocol_acceptsPort() {
        LogSource source = service.create("web-01", "syslog", "ssh-auth", 5001);
        assertEquals("syslog", source.protocol);
        assertEquals(5001, source.port);
    }

    @Test
    void create_fileProtocol_requiresPathAndDoesNotReservePort() {
        LogSource source = service.create("web-01", "file", "ssh-auth", 0, "/var/log/auth.log");
        assertEquals("file", source.protocol);
        assertEquals("/var/log/auth.log", source.path);
        assertThrows(IllegalArgumentException.class,
                () -> service.create("web-02", "file", "ssh-auth", 0, ""));
    }

    @Test
    void activateSync_success_setsActive() {
        LogSource s = service.create("web-01", "tcp", "ssh-auth", 5001);
        LogSource done = service.activateSync(s.id);
        assertEquals("active", done.status);
        assertEquals("active", store.find(s.id).status);
    }

    @Test
    void activateSync_failure_setsFailed() {
        LogSource s = service.create("web-01", "tcp", "ssh-auth", 5001);
        doThrow(new ActivationFailedException("校验失败")).when(coordinator).activate(any(), any());
        LogSource done = service.activateSync(s.id);
        assertEquals("failed", done.status);
        assertEquals("failed", store.find(s.id).status);
    }

    @Test
    void delete_removesFile() {
        LogSource s = service.create("web-01", "tcp", "ssh-auth", 5001);
        service.delete(s.id);
        assertThrows(NotFoundException.class, () -> store.find(s.id));
    }
}
