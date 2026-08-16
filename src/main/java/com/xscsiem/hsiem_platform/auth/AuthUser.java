package com.xscsiem.hsiem_platform.auth;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/** 控制台用户(story-08 RBAC,infra/auth/users.yaml)。密码存 BCrypt 哈希。 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class AuthUser {

    public String id;
    public String username;
    public String passwordHash;
    /** admin / analyst / ops / audit(角色权限矩阵见 AuthService.ROLE_PERMS)。 */
    public String role;
    /** active / disabled。 */
    public String status = "active";
    public String createdAt;
}
