package com.xscsiem.hsiem_platform.auth;

/** 权限不足(403):已认证但角色无该操作权限。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
