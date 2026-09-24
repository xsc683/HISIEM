# 控制台用户与权限(story-08 RBAC)

**用户、角色和审计记录都存在 PostgreSQL**,不在文件里。`infra/auth/users.yaml` 现在是一个
**有意留空的旧版种子文件**——它之所以存在,是为了让首次启动走 `SIEM_BOOTSTRAP_PASSWORD`
引导分支,而不是去导入一个硬编码的旧用户与哈希。真实用户只通过官方认证 API 创建。

角色权限矩阵以后端 `AuthService.ROLE_PERMS` 为准:
`admin`(全量)/ `analyst`(告警读写 + SOAR 读/执行/审批)/ `ops`(数据源写 + 健康读)/ `audit`(只读)。

## 首启口令

**没有默认账号。** 首次启动(PostgreSQL 用户表为空)时,后端要求显式注入首启管理员口令:

```bash
# PowerShell
$env:SIEM_BOOTSTRAP_PASSWORD = '<至少 12 位临时口令>'
# WSL
export SIEM_BOOTSTRAP_PASSWORD='<至少 12 位临时口令>'
```

`app.auth.bootstrap-password` 的默认值是空;为空时 `AuthService.bootstrap()` 直接抛
`IllegalStateException("未配置首启管理员口令，请设置 SIEM_BOOTSTRAP_PASSWORD")`,**进程启动失败**。
因此「首次启动会自动得到一个 admin/admin123」的说法在当前实现下不成立——那只是单元测试夹具里的口令。

引导出的 admin 带 `passwordChangeRequired=true`。在任何 `/api/**` 路径上(除 `login`/`password`/`me`/`logout`)
它都会先返回 **428 `PASSWORD_CHANGE_REQUIRED`**,必须轮换口令后才能继续;口令长度下限 12 位。

> **Implementation Gap(本轮只登记,不改代码)**:`AuthService` 的类 javadoc 仍写着
> 「密码 BCrypt 哈希,存 `infra/auth/users.yaml`(文件 + Git)」。那是旧实现的描述,与当前
> 「PostgreSQL 是用户/角色/审计的唯一来源」相矛盾。行为本身是对的,错的是那句注释。

## 受保护端点

`SecurityConfig` 实际生效的是三条规则:

| 范围 | 要求 |
| --- | --- |
| `/api/auth/login`、`/actuator/health`、`/actuator/info`、`/hello` | 无需认证 |
| `/actuator/**`(其余 actuator 端点) | 需登录 + `ADMIN` |
| `/api/**` | **需登录** |
| 其他(静态资源、SPA 路由) | 无需认证 |

注意第三行:**所有 `/api/**` 都过认证链**,包括模板、数据源、规则、健康这些 GET。
「只读视图保持开放」是旧行为,已不成立。更细的写权限(设置写操作、`/api/detection-rules/deploy`、
`/api/auth/users|roles|audit-logs`)仍由各控制器按 `ROLE_PERMS` 判定为 `admin`。

`/api/internal/**` 走的是**另一条**独立安全链(服务间凭据,不校验用户会话),不在这张表里,
见 [agent-integration.md](../../docs/agent-integration.md)。
