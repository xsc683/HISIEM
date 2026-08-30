package com.xscsiem.hsiem_platform.auth;

/** 对外用户视图。认证材料永远不通过管理 API 返回。 */
public record AuthUserView(
        String id,
        String username,
        String role,
        String status,
        String createdAt,
        boolean passwordChangeRequired) {

    public static AuthUserView from(AuthUser user) {
        return new AuthUserView(user.id, user.username, user.role, user.status,
                user.createdAt, user.passwordChangeRequired);
    }
}
