# Story 08 — 用户与权限(RBAC)

> **元信息**
> - 关联模块:08 产品设计 §5.6 系统设置(用户权限)+ §7 权限与安全;对齐 [security-rbac.md](../design/security-rbac.md) 的 ES 侧 RBAC
> - 优先级:Phase 4.3 已交付(原 P2)
> - 状态:✅ 已实现（登录/会话/用户角色 CRUD + 方法级权限 + 审计 + V6 首次登录改密）
> - 依赖:Spring Boot + React 控制台;PostgreSQL/Flyway V1-V7;ES 侧最小权限脚本独立维护
>
> **填写完成度 checklist**
> - [x] **1 用户故事**:「作为…我希望…以便…」
> - [x] **2 背景/目标**:目标可度量、非目标边界明确,引用 08 §7 与 security-rbac.md
> - [x] **3 用户旅程**:旅程表每行含「用户操作(字段级)/ 界面反馈 / 异常/边界」三列
> - [x] **4.1 FR**:每条含「说明」列(字段/阈值/校验),优先级填 P2
> - [x] **4.2 非功能**:性能/权限安全/异常回滚/可观测,阈值具体
> - [x] **4.3 字典**:本 story 用到的枚举全部取自 §4.3;新增 `user.role`/`user.status`/`perm.action` 已在 _template §4.3 登记
> - [x] **5.2 API**:每个端点有「请求/响应样例」+ 4xx 约定
> - [x] **5.3 存储**:每个存储对象 mapping 形状 + infra 是否已落地
> - [x] **5.4 同步链路**:写 infra 文件的每个功能都填同步/校验/生效/回滚
> - [x] **7 验收**:正常+异常+边界,Given-When-Then + 量化断言
> - [x] **10 决策**:存储选型/生效机制收敛为「决定」

---

## 1. 用户故事

**作为** [安全管理员],
**我希望** [给不同角色(admin/analyst/ops/audit)授予不同模块的读/写/导出权限,并记录敏感操作审计],
**以便** [分析师能处置告警但不能改规则、ops 能停采但不能动告警、audit 只能导出审计,避免越权误操作,满足最小权限]。

## 2. 背景与目标

### 2.1 背景(当前痛点)
- 历史状态是接口无用户体系、任何能访问端口的人均可读写。(✅ **已由本 story 落地**:Bearer 会话、四角色权限矩阵和统一 401/403 错误)
- 控制台四角色 × 模块 × 动作矩阵已落地；会话、用户、失败计数和审计已迁移到 PostgreSQL，`infra/auth/users.yaml` 仅作为首次启动兼容导入源。
- 数据源生效、规则启停、告警处置、案件更新等敏感操作均由 Spring Security 方法级授权保护，并记录操作人/时间/动作。

### 2.2 目标(可度量)
- 登录鉴权 + 四角色授权:未登录访问任意 API → 401;角色无权限动作 → 403,拒绝率 100%。
- 敏感写操作全部带审计记录(operator + time + 变更字段),可查询；文件导出后置。
- `/api/**` 默认要求 Bearer 会话；登录、健康探针等明确公共端点例外。方法级 `@PreAuthorize` 再按角色限制具体写操作。

### 2.3 非目标(明确不做)
- 不做用户自助注册/找回；管理员创建临时口令后由用户自助完成首次轮换，复杂度策略保持最小可执行边界（至少 12 位）。
- 不做多租户数据隔离(security-rbac.md §3 备注,后置)。
- 不做字段级隐藏 FLS(security-rbac.md §3 有示例,MVP 单机不启用,多租户前必做)。

## 3. 用户旅程

```
① admin 登录 → ② 用户管理(建/禁用户) → ③ 按矩阵授权 → ④ 敏感操作审计可查 → ⑤ audit 导出
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ① | 输入用户名/密码登录 | 成功→跳首页,导航显示当前角色；`passwordChangeRequired=true` 时强制打开改密弹窗 | 密码错→提示 + 401;用户 disabled→403「账号已禁用」 |
| ② | admin 打开「系统设置→用户权限」新建用户(用户名/临时密码/角色) | 列表新增一行(status=active,passwordChangeRequired=true) | 用户名重复→400;角色不在 §4.3 字典→400;密码少于 12 位→400 |
| ③ | 查看角色矩阵(模块×动作)或为单个用户改角色 | 变更落库并提示生效 | 改自己角色→二次确认;删除最后一名 admin→409 阻止 |
| ④ | 敏感操作(如规则启停)执行 | 正常执行 + 审计落库 | 无权限(analyst)→403 + 前端按钮置灰 |
| ⑤ | audit 登录,导出审计 | 下载 CSV/JSON | 非 audit/export 权限调导出→403 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 登录鉴权(session/token) | 已实现 | `POST /api/auth/login`;失败 401;Bearer 会话默认 8 小时,数据库保存 token hash |
| FR-2 | 用户 CRUD + 启停 | 已实现 | 用户名唯一;角色∈§4.3 字典;disabled 用户登录被拒 |
| FR-3 | 角色×模块×动作矩阵授权 | 已实现 | `@PreAuthorize` + 角色权限集合;未授权动作→403 |
| FR-4 | 敏感操作审计 | 已实现 | 记录 operator + time + action/target;审计写入 PostgreSQL |
| FR-5 | 前端按角色渲染 | 已实现 | 前端按当前用户角色隐藏/禁用动作;接口层 403 兜底 |
| FR-6 | 审计查询 | 已实现 | 通过控制面 API 查询审计记录;独立文件导出仍可后置 |
| FR-7 | 首次登录密码轮换 | 已实现 | `POST /api/auth/password` 校验当前口令和新口令（至少 12 位）；轮换前业务 API 返回 428 |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 登录/鉴权接口 P95 <300ms;权限变更在下一次鉴权时生效 |
| 权限/安全 | 全部 API 默认 deny;401/403 语义区分;密码仅存 hash(BCrypt),禁止返回/日志打印;敏感写操作全量审计 |
| 异常恢复/回滚 | 用户/角色/会话写 PostgreSQL 事务化:校验失败回滚;并发更新由 Service 统一处理 |
| 可观测 | 审计日志可查询;403 拒绝计数可统计;用户/角色版本可追溯 |
| 可维护性 | 运行时用户/角色存 PostgreSQL;`infra/auth/users.yaml` 仅用于首次导入;sources/rules 等配置继续走 Git/PR |

### 4.3 枚举与字段字典

> 取值与 _template §4.3 一致;本 story 新增 `user.role`/`user.status`/`perm.action`,已在 _template §4.3 登记(权威来源=该表),禁止自创取值。

| 字段 | 取值集合 | 权威来源 | 说明 |
| --- | --- | --- | --- |
| `user.role` | `admin` / `analyst` / `ops` / `audit` | _template §4.3(P2 登记) | console 四角色,对齐 08 §7 矩阵 |
| `user.status` | `active` / `disabled` | _template §4.3(P2 登记) | disabled→登录 403 |
| `perm.action` | `read` / `write` / `export` | _template §4.3(P2 登记) | 动作粒度;矩阵行=模块,列=动作 |
| `alert.status` / `alert.analyst_verdict` / `alert.severity` | 见 _template §4.3 | _template §4.3 | 复用既有枚举,不重定义 |

## 5. 后端架构

```
React 前端 → /api(Spring Security + BearerSessionFilter) → AuthService(角色权限) + PostgreSQL → 业务 Service → 审计记录(PostgreSQL)
```

### 5.1 组件与职责
| 组件 | 职责 |
| --- | --- |
| `BearerSessionFilter` + `SecurityConfig` | 全局鉴权:未登录 401、无权限 403;HTTP 层无状态,会话事实由 PostgreSQL 保存 |
| `AuthService` | 登录、用户 CRUD/启停、BCrypt 密码、登录失败限制和权限判断 |
| `@PreAuthorize` | Controller 方法级角色授权,默认拒绝未认证请求 |
| `JdbcControlPlaneStore` | 用户、会话、失败计数、角色、审计写入 PostgreSQL |

### 5.2 API 契约

```
POST /api/auth/login        → 请求 {username,password};200 {token, user{role}}
POST /api/auth/password     → 请求 {currentPassword,newPassword};200 {username,passwordChangeRequired:false}
GET  /api/auth/me           → 200 {username, role, permissions:[...]}
GET  /api/users             → 200 [{id, username, role, status, createdAt}]
POST /api/users             → 请求 {username, password, role};201 {id}
PATCH /api/users/{id}       → 请求 {role?|status?};200 {role/status}
GET  /api/roles             → 200 角色×模块×动作矩阵
GET  /api/audit-logs        → 200 [{operator, action, module, time, detail}](仅 audit/export)
```

**请求/响应样例**:

```
POST /api/users → 请求
{ "username": "analyst01", "password": "****", "role": "analyst" }
  // username string≤64 必填;password string 必填;role 枚举见 §4.3
→ 201 { "id": "u-002", "username": "analyst01", "role": "analyst",
        "status": "active", "createdAt": "2026-08-16T10:00:00Z" }

PATCH /api/users/u-001 → 请求 { "status": "disabled" }
→ 200 { "id": "u-001", "status": "disabled", "updatedAt": "2026-08-16T11:00:00Z" }

GET /api/auth/me → 200 { "username": "analyst01", "role": "analyst",
  "permissions": [{ "module": "alerts", "actions": ["read","write"] }] }
```

**4xx 错误码约定**(沿用 _template,不另造):

| 错误码 | 语义 | 典型触发 |
| --- | --- | --- |
| 400 | 参数非法 | 用户名重复 / role 不在字典 / 密码为空 |
| 401 | 未鉴权 | 无 token / token 过期 / 登录失败 |
| 403 | 无权限 | 角色无对应模块×动作权限;disabled 用户访问 |
| 404 | 资源不存在 | 用户 id 查无 |
| 409 | 冲突 | 并发更新用户 / 删除最后一名 admin |

### 5.3 存储

| 数据 | 存储 | 关键字段(mapping 形状) | infra 对应 | 已落地? |
| --- | --- | --- | --- | --- |
| 用户/角色 | PostgreSQL `users` / `roles` | id / username / password_hash / role / status | Flyway V1 | ✅ 已落地 |
| 会话/失败计数 | PostgreSQL `auth_sessions` / `login_attempts` | token_hash / expires_at / attempts / locked_until | Flyway V2 | ✅ 已落地 |
| 审计日志 | PostgreSQL `audit_logs` | actor / action / target / created_at | Flyway V1 | ✅ 已落地 |

### 5.4 配置同步与生效链路

> PostgreSQL 是运行时控制面事实来源；旧 `infra/auth/users.yaml` 仅在空库首次启动时导入。数据源/规则等配置仍按各自 repo→deploy 链路生效。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 用户/角色/会话 | PostgreSQL | Flyway schema + Service 参数校验 | 事务提交后立即生效 | 事务回滚,不产生半写 |
| 兼容用户声明 | infra/auth/users.yaml | YAML 解析 + 首次导入 | 应用首次启动导入 PostgreSQL | 保留旧文件,导入失败阻止启动 |

## 6. 数据流实现

```
登录 → [BearerSessionFilter 查 PostgreSQL 会话] → [@PreAuthorize 角色校验] → [业务接口执行] → [控制面审计]
边界:无 token/过期 → 401;角色无权 → 403,不执行业务
```

| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |
| 鉴权 | token | 校验有效期 + 角色 | 用户上下文 | 无效/过期→401 |
| 授权 | role + endpoint/method | `@PreAuthorize` + AuthService 权限集合 | allow/deny | deny→403 |
| 审计 | 业务写操作 | 记录 operator/time/action/target | PostgreSQL `audit_logs` | 与控制面事务保持一致 |

## 7. 验收标准(DoD)

- **正常**:**Given** 已启用 RBAC 且有 admin/analyst 两用户 **When** admin 调用 `PATCH /api/detection-rules/r-01` 修改 enabled **Then** 返回 200,`infra/rules/r-01.yaml` 的 enabled 变更,审计记录出现 `operator=admin, action=rule_toggle`。
- **异常**:**Given** 同一环境 **When** analyst(仅 read)调用上述启停 **Then** 返回 403,enabled 未变更,403 拒绝计数 +1。
- **边界**:**Given** 未登录 **When** 调用 `GET /api/alerts` **Then** 返回 401;带有效 Bearer 会话且角色有读权限时返回 200。
- **异常/回滚**:**Given** PostgreSQL 用户与角色记录有效 **When** 提交一份不存在角色的用户变更 **Then** 返回 400,数据库事务回滚,无部分生效。
- **异常/并发**:**Given** 两名 admin 同时改同一用户角色 **When** 后者提交 **Then** 返回 409 提示刷新重试,不覆盖先到者写入。

## 8. 业界参考 / 最佳实践

| 参考 | 借鉴 |
| --- | --- |
| [Wazuh 用户管理](https://documentation.wazuh.com/current/user-manual/reference/restful/security.html) | 用户/角色拆分 + API 鉴权 |
| [OpenSearch Security](https://opensearch.org/docs/latest/security/access-control/) | 角色 × 动作 × 索引权限模型 + FLS |
| Splunk 角色权限 | 默认 deny + 最小权限原则 |

## 9. 开放问题

- ES 侧 `siem_ingest`/`siem_analyst` 与 console 四角色的映射:console 侧 RBAC 已完成；ES 请求当前由后端共享客户端代理，细粒度角色映射、多租户 FLS 后置。

## 10. 设计决策(ADR 式)

### ADR-1 [用户/会话/审计存储选型]
- **背景**:运行时认证需要过期会话、登录失败限制、用户启停和审计查询；这些数据需要事务与并发安全。
- **选项**:A. YAML + Git / B. ES 索引 / C. PostgreSQL 控制面。
- **取舍**:YAML 适合声明配置但不适合会话和频繁状态更新；ES 不提供本项目所需的控制面关系约束；PostgreSQL 与阶段 4.1 控制面一致，Flyway 可迁移且便于事务回滚。
- **决定**:采用 **C**。`infra/auth/users.yaml` 仅保留首次启动兼容导入，运行时用户、会话、失败计数和审计均由 PostgreSQL 管理。

### ADR-2 [运行时生效机制]
- **背景**:用户角色或状态变更后，后续请求必须立即使用最新控制面数据。
- **决定**:用户/角色变更以 PostgreSQL 事务提交为生效点；Bearer 会话按过期时间校验，disabled 用户不能继续登录。配置类数据源/规则仍走各自文件同步链路。
