package com.xscsiem.hsiem_platform.tenant;

/** 请求线程的租户边界；后台 Worker 从持久化执行记录读取 tenantId。 */
public final class TenantContext {

    public static final String DEFAULT_TENANT = "default";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String id() {
        String value = CURRENT.get();
        return value == null ? DEFAULT_TENANT : value;
    }

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
