package com.xscsiem.hsiem_platform.auth;

/** 未认证(401):无 token / token 无效 / 会话过期。 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
