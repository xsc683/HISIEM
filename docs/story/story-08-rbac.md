# Story 08 — 用户与权限(RBAC)

> **元信息**
> - 关联模块:08 产品设计 §5.6 系统设置(用户权限·远期)+ §7 权限与安全;对齐 [security-rbac.md](../design/security-rbac.md) 的 ES 侧 RBAC
> - 优先级:P2
> - 状态:✅ 已实现(dd3e32f;登录/会话/用户角色 CRUD + 权限矩阵 + 审计)
> - 依赖:console 前后端骨架(b2051fd);ES 侧 RBAC 文档已就绪(security-rbac.md,Phase 3.4);story-03(规则启停)/ story-04(告警处置)/ story-05(停采)提供写操作鉴权点
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
- console(Spring Boot + React 骨架)当前**无用户体系**:接口直接可调,任何能访问端口的人可读写(与 ES 9200 未开认证同源风险,见 security-rbac.md §1)。
- 08 §7 已定 console 四角色 × 模块 × 动作矩阵,但未落地;ES 侧已有 `siem_ingest`/`siem_analyst` 角色(security-rbac.md §3),console 侧产品角色需与之对齐。(✅ **已由本 story 落地,dd3e32f**:四角色 × 模块 × 动作矩阵 + AuthInterceptor 鉴权 + 审计日志)
- 敏感操作(数据源生效、规则启停、批量 close、verdict)目前无鉴权无审计。(✅ **已落地**:AuthInterceptor 保护写操作,operator 审计)

### 2.2 目标(可度量)
- 登录鉴权 + 四角色授权:未登录访问任意 API → 401;角色无权限动作 → 403,拒绝率 100%。
- 敏感写操作全部带审计记录(operator + time + 变更字段),可查询可导出。
- 单机 MVP 可关闭 RBAC(`auth.enabled=false`)保持现状直连;开启后全部接口按矩阵生效。

### 2.3 非目标(明确不做)
- 不做用户自助注册/找回/密码策略(导入式维护,见 §10 ADR-1)。
- 不做多租户数据隔离(security-rbac.md §3 备注,后置)。
- 不做字段级隐藏 FLS(security-rbac.md §3 有示例,MVP 单机不启用,多租户前必做)。

## 3. 用户旅程

```
① admin 登录 → ② 用户管理(建/禁用户) → ③ 按矩阵授权 → ④ 敏感操作审计可查 → ⑤ audit 导出
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ① | 输入用户名/密码登录 | 成功→跳首页,导航显示当前角色 | 密码错→提示 + 401;用户 disabled→403「账号已禁用」 |
| ② | admin 打开「系统设置→用户权限」新建用户(用户名/角色) | 列表新增一行(status=active) | 用户名重复→400;角色不在 §4.3 字典→400 |
| ③ | 查看角色矩阵(模块×动作)或为单个用户改角色 | 变更落库并提示生效 | 改自己角色→二次确认;删除最后一名 admin→409 阻止 |
| ④ | 敏感操作(如规则启停)执行 | 正常执行 + 审计落库 | 无权限(analyst)→403 + 前端按钮置灰 |
| ⑤ | audit 登录,导出审计 | 下载 CSV/JSON | 非 audit/export 权限调导出→403 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 登录鉴权(session/token) | P2 | `POST /api/auth/login`;失败 401;`auth.enabled=false` 时跳过(单机直连过渡,与现状一致) |
| FR-2 | 用户 CRUD + 启停 | P2 | 用户名唯一(≤64 字符);角色∈§4.3 字典;disabled 用户登录被拒(403) |
| FR-3 | 角色×模块×动作矩阵授权 | P2 | 按 08 §7 矩阵实现;动作∈{read/write/export};默认 deny(未授权动作→403) |
| FR-4 | 敏感操作审计 | P2 | 记录 operator + time + 变更字段(复用 `alert.operator` 同款模式);可查询/导出(audit) |
| FR-5 | 前端按角色渲染 | P2 | 无权限动作按钮置灰/隐藏;接口层 403 兜底(前端隐藏不替代服务端鉴权) |
| FR-6 | 审计导出 | P2 | 仅 audit/export 权限;导出范围按模块筛选 |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 登录/鉴权接口 P95 <300ms;角色缓存 60s,不影响业务接口 P95 |
| 权限/安全 | 全部 API 默认 deny;401/403 语义区分;密码仅存 hash(BCrypt),禁止返回/日志打印;敏感写操作全量审计 |
| 异常恢复/回滚 | 用户/角色写 infra/auth/*.yaml 原子化:校验失败保留旧文件 + 操作=failed;并发改同一用户→409(文件锁/`_seq_no`) |
| 可观测 | 审计日志可查询;403 拒绝计数可统计;用户/角色版本可追溯 |
| 可维护性 | 用户/角色即代码(YAML + Git),console 只读 + 启停,编辑走 Git/PR;与 log-sources/rules 同一同步链路 |

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
React 前端 → /api(Spring Boot AuthInterceptor 鉴权) → PolicyService(infra/auth/policy.yaml) + UserService(infra/auth/users.yaml) → AuditService(ES siem-audit-*)
```

### 5.1 组件与职责
| 组件 | 职责 |
| --- | --- |
| AuthInterceptor | 全局鉴权:未登录 401、无权限 403;`auth.enabled=false` 时直通(单机过渡) |
| UserService | 用户 CRUD/启停,写 infra/auth/users.yaml |
| PolicyService | 加载角色矩阵(infra/auth/policy.yaml),提供 hasPermission(role, module, action) |
| AuditService | 敏感写操作审计落库(ES `siem-audit-*`) |

### 5.2 API 契约

```
POST /api/auth/login        → 请求 {username,password};200 {token, user{role}}
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
| 用户声明 | `infra/auth/users.yaml` | id / username / password_hash / role / status | 待本 story 建 | 本 story 建 |
| 角色矩阵 | `infra/auth/policy.yaml` | module / action / roles[] | 待本 story 建 | 本 story 建 |
| 审计日志 | ES `siem-audit-*`(按天) | operator(keyword) / action(keyword) / module(keyword) / time(date) / detail(text) | infra/elasticsearch/siem-audit-template.json | 本 story 建 |

### 5.4 配置同步与生效链路

> console 读 infra/auth/*.yaml 做鉴权/用户数据;变更走 repo → deploy 同一链路,禁止另起通道。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 用户/角色声明 | infra/auth/users.yaml, policy.yaml | YAML schema + 引用校验(角色存在 / 矩阵合法) | console 热加载(或 restart console) | 保留旧文件,操作=failed |
| 审计索引模板 | infra/elasticsearch/siem-audit-template.json | 模板校验 | 重新应用模板 | 保留旧模板 |

## 6. 数据流实现

```
登录 → [AuthInterceptor 校验] → [PolicyService hasPermission] → [业务接口执行] → [AuditService 落审计]
边界:auth.enabled=false → 直通;未授权 → 403 不执行业务、不落审计
```

| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |
| 鉴权 | token | 校验有效期 + 角色 | 用户上下文 | 无效/过期→401 |
| 授权 | role + module + action | policy 矩阵查表 | allow/deny | deny→403 + 拒绝计数 |
| 审计 | 业务写操作 | 记录 operator/time/变更字段 | ES siem-audit-* | 审计写失败不影响业务(仅告警日志) |

## 7. 验收标准(DoD)

- **正常**:**Given** 已启用 RBAC 且有 admin/analyst 两用户 **When** admin 调用 `PATCH /api/detection-rules/r-01` 修改 enabled **Then** 返回 200,`infra/rules/r-01.yaml` 的 enabled 变更,审计记录出现 `operator=admin, action=rule_toggle`。
- **异常**:**Given** 同一环境 **When** analyst(仅 read)调用上述启停 **Then** 返回 403,enabled 未变更,403 拒绝计数 +1。
- **边界**:**Given** 未登录 **When** 调用 `GET /api/alerts` **Then** 返回 401;`auth.enabled=false` 时同一调用返回 200(单机直连过渡)。
- **异常/回滚**:**Given** infra/auth/users.yaml 已有一份合法用户表 **When** 提交一份 role 不在字典的用户 **Then** 返回 400,旧文件字节不变,无部分生效。
- **异常/并发**:**Given** 两名 admin 同时改同一用户角色 **When** 后者提交 **Then** 返回 409 提示刷新重试,不覆盖先到者写入。

## 8. 业界参考 / 最佳实践

| 参考 | 借鉴 |
| --- | --- |
| [Wazuh 用户管理](https://documentation.wazuh.com/current/user-manual/reference/restful/security.html) | 用户/角色拆分 + API 鉴权 |
| [OpenSearch Security](https://opensearch.org/docs/latest/security/access-control/) | 角色 × 动作 × 索引权限模型 + FLS |
| Splunk 角色权限 | 默认 deny + 最小权限原则 |

## 9. 开放问题

- ES 侧 `siem_ingest`/`siem_analyst` 与 console 四角色的映射(08 §12 已列):MVP 单 admin 直连时不需要;多用户前须收敛为「console 复用/代理 ES 认证」或独立用户体系。

## 10. 设计决策(ADR 式)

### ADR-1 [用户/角色存储选型]
- **背景**:用户/角色数据量小(个位数)、变更低频,需可审计可版本化;与 log-sources/rules 的「配置即代码」口径应一致。
- **选项**:A. YAML + Git(`infra/auth/`)/ B. ES 索引 / C. 关系库(H2/Postgres)
- **取舍**:A 复用既有 rsync 同步链路与 Git 审计,无新依赖,运维面最小;代价是并发写需文件锁/`_seq_no`(本项目单管理元可接受)。B/C 引入新依赖与登录体系,小团队不值当。
- **决定**:A. 用户/角色声明存 `infra/auth/*.yaml`(文件 + Git),console 只读 + 启停,编辑走 Git/PR;infra 尚无落地(见 §5.3,本 story 建)。

### ADR-2 [生效机制(写 infra 后如何生效)]
- **背景**:console 进程持有用户/角色数据的启动快照;不重载则变更不生效。
- **选项**:A. 热加载(监听文件变更 / 定期轮询)/ B. 重启 console 容器
- **取舍**:A 免重启、失败可回滚(保留旧文件);B 简单但中断 console(登录/鉴权瞬断)。
- **决定**:A. console 按文件变更热加载 auth 配置,加载失败保留旧配置、状态=failed;回滚口径与 §5.4 一致。
