package com.xscsiem.hsiem_platform.control;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * God port 拆分证据：按有界上下文拆出的窄持久化端口必须由同一个控制面实现提供， 以便业务 Service 只依赖自己需要的那一面，而运行时仍收敛到单一 MyBatis store 单例。
 */
@SpringBootTest
class ControlPlanePortAssemblyTest {

    @Autowired private AuthStore authStore;

    @Autowired private NotificationStore notificationStore;

    @Autowired private CaseStore caseStore;

    @Autowired private TaskStore taskStore;

    @Autowired private LifecycleOutboxStore lifecycleOutboxStore;

    @Autowired private MyBatisControlPlaneStore concrete;

    @Test
    void narrowPortsAreProvidedBySingleControlPlaneStore() {
        assertNotNull(authStore);
        assertNotNull(notificationStore);
        assertNotNull(caseStore);
        assertNotNull(taskStore);
        assertNotNull(lifecycleOutboxStore);
        assertNotNull(concrete);
        assertSame(concrete, authStore);
        assertSame(concrete, notificationStore);
        assertSame(concrete, caseStore);
        assertSame(concrete, taskStore);
        assertSame(concrete, lifecycleOutboxStore);
    }
}
