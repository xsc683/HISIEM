package com.xscsiem.hsiem_platform.entrypoints;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.xscsiem.hsiem_platform.tenant.TenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 回归测试:独立 SOAR worker 的 composition root 必须能够完整装配。
 *
 * <p>此前的用例只断言注解与 WebApplicationType,从不真正创建 Spring 上下文,因此漏掉了 "组件扫描能看到的 Bean 依赖了一个没被 MapperScan
 * 覆盖的映射器"这类启动缺陷 —— worker 进程在部署时才会失败。这里用 H2(PostgreSQL 模式)真实装配上下文,并显式解析 {@code TenantMapper}:{@code
 * AuthService}(由 {@code com.xscsiem.hsiem_platform} 组件扫描 装配)依赖 {@code TenantService ->
 * MyBatisTenantRepository -> TenantMapper},缺少该扫描 会让整个 worker 无法启动。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SoarWorkerContextTest {

    @Autowired private TenantMapper tenantMapper;

    @Test
    void compositionRootStartsAndScansTheMappersItsComponentScanRequires() {
        assertNotNull(
                tenantMapper,
                "TenantMapper must be scanned by SoarWorkerApplication: AuthService requires "
                        + "TenantService -> MyBatisTenantRepository -> TenantMapper, so without "
                        + "it the standalone worker cannot start");
    }
}
