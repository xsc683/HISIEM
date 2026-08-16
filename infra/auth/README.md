# 控制台用户与权限(story-08 RBAC)

`users.yaml` 存控制台用户(密码 BCrypt 哈希)。角色权限矩阵见后端 `AuthService.ROLE_PERMS`:
admin(全量)/ analyst(告警)/ ops(数据源+健康)/ audit(只读)。

## 默认账号

首次启动无用户时自动引导:**admin / admin123**。**上线前必须改密**(登录后在「⑧ 用户与权限」新增用户或改密;或直接编辑本文件后重启)。

## 受保护端点(MVP 范围,增量扩大)

- `/api/settings/**` 写操作 + `/api/detection-rules/deploy` → 需登录 + admin
- `/api/auth/users|roles|audit-logs` → 需登录 + admin
- 只读视图(模板/数据源/规则/健康 GET)保持开放
