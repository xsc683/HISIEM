package com.xscsiem.hsiem_platform.soar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SoarPlaybookRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void reloadAcceptsWhitelistedPlaybook() throws Exception {
        Files.writeString(tempDir.resolve("valid.yaml"), """
                id: alert-triage-test
                name: 测试处置
                version: "1.0"
                resourceTypes: [alert]
                steps:
                  - id: acknowledge-alert
                    name: 确认告警
                    action: alert.set_status
                    with:
                      status: acknowledged
                """);

        SoarPlaybookRegistry registry = new SoarPlaybookRegistry(tempDir.toString());
        registry.initialize();

        assertEquals(1, registry.list().size());
        assertEquals("alert.set_status", registry.get("alert-triage-test").steps().getFirst().action());
    }

    @Test
    void reloadRejectsArbitraryAction() throws Exception {
        Files.writeString(tempDir.resolve("unsafe.yaml"), """
                id: unsafe-playbook
                name: 不安全动作
                version: "1.0"
                resourceTypes: [alert]
                steps:
                  - id: run-command
                    name: 执行命令
                    action: shell.exec
                """);

        SoarPlaybookRegistry registry = new SoarPlaybookRegistry(tempDir.toString());
        assertThrows(IllegalArgumentException.class, registry::initialize);
    }
}
