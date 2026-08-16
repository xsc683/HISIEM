package com.xscsiem.hsiem_platform.onboarding;

/** 端口已被其他数据源占用 → HTTP 409(创建时校验)。 */
public class PortConflictException extends RuntimeException {
    public PortConflictException(int port) {
        super("端口 " + port + " 已被其他数据源占用");
    }
}
