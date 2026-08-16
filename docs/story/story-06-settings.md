# Story 06 — 系统设置·资产关键度

> **元信息**
> - 关联模块:08 产品设计 §5.6 系统设置
> - 优先级:P2
> - 状态:草稿
> - 依赖:实体风险聚合已实现(entity-risk.py + asset-criticality.json)
>
> **填写完成度 checklist**(P1 深化判定,提交评审前逐项自检;参照 P0 既有结论深化,不重开已定决策)
> - [x] **1 用户故事**:按「作为…我希望…以便…」填写,聚焦用户价值而非实现
> - [x] **2 背景/目标**:目标可度量、非目标明确边界,引用既有决策(01 F-R10 检测即代码)
> - [x] **3 用户旅程**:旅程表每行含「用户操作(字段级) / 界面反馈 / 异常/边界」三列
> - [x] **4.1 FR**:每条含「说明」列,写清字段/阈值/校验/接口,优先级填 P2
> - [x] **4.2 非功能**:含性能/权限安全/异常回滚/可观测/可维护性 五维,阈值具体
> - [x] **4.3 字典**:本 story 用到的枚举(type/level)取自 _template §4.3(`asset.criticality`),未自创
> - [x] **5.2 API**:每个端点有「请求/响应逐字段样例」+ 4xx 错误码约定
> - [x] **5.3 存储**:每个存储对象有 mapping 形状示例 + 是否已在 infra 落地标注
> - [x] **5.4 同步链路**:asset-criticality.json 的同步/校验/生效/回滚已填
> - [x] **7 验收**:覆盖 正常+异常+边界+回滚,Given-When-Then + 量化断言
> - [x] **10 决策**:存储选型/生效机制已收敛为「决定」(ADR-1/ADR-2),§9 仅留真正未决问题

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 在设置页编辑资产关键度(IP/用户/主机 → Low/Medium/High/Extreme),
**以便** 实体风险聚合按资产重要度加权,让"域控上的 Medium"不被"实验室机器上的 High"盖过。

## 2. 背景与目标

### 2.1 背景(当前痛点)
- `asset-criticality.json`(Phase 3.5)已存在,被 `entity-risk.py` 读取;当前样本:`ip:{"10.0.0.1":2.0}`、`user:{"root":1.5,"admin":1.5}`、`host:{"server01":2.0}`,文件存的是**权重数值**。
- `entity-risk.py` 现状:只对 `source.ip`、`user.name` 两类实体加权(查询体内无 host 聚合),`score = Σalert.risk_score × 权重`;实体未配置时按权重 **1.0(=Medium)** 兜底;`--write` 幂等(`_id=type-value`)写 `siem-entity-risk`。
- 缺:可视化管理界面(现在要手改 JSON);且**保存 JSON ≠ 立即生效**——新权重要等一次 `entity-risk.py` 重算才反映到风险分。

### 2.2 目标(可度量)
- 设置页可视化增删改/批量导入资产关键度,保存后**触发一次实体风险重算**,风险分反映新权重。
- 级别↔权重换算唯一收敛一处:Low 0.5 / Medium 1 / High 1.5 / Extreme 2(对齐 Elastic 模型);文件存数值、UI 下拉存级别。
- 保存后(触发聚合)该实体的 `risk_score` 精确等于 `round(Σalert.risk_score × 新权重, 1)`(可量化断言,见 §7)。

### 2.3 非目标
- 不做多租户/角色化设置。
- 不做资产自动发现(资产清单来自人工/导入)。
- **MVP 不做 host 加权聚合**(见 §5/§9 决策:host 仅存储,`entity-risk.py` 仍只对 source.ip/user.name 加权)。

## 3. 用户旅程

```
① 打开系统设置 → ② 资产关键度 → ③ 检索/新增/修改/批量导入资产 → ④ 保存 → ⑤ 触发实体风险重算 → ⑥ 风险分/列表刷新
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ③ | 新增 IP/用户 → 下拉选级别(Low/Medium/High/Extreme) | 列表即时更新(级别 + 换算后权重) | 重复 key→弹窗提示覆盖/合并;IP/主机名格式非法→红提示 |
| ③ | 批量导入:粘贴/上传 CSV(type,key,level) | 导入结果:成功 N / 跳过 M / 失败 K(逐行原因) | 非法行→跳过并列出原因,不整体失败 |
| ④ | 点"保存" | 写入成功 toast + 审计记录 | 写文件失败→回滚,提示失败、配置不破坏;重复 PUT 同值→幂等成功 |
| ⑤ | (自动)保存成功后触发实体风险重算 | 任务执行中 → 完成(可看上次重算时间) | 重算失败→旧风险分保留,可手动重跑 |
| ⑥ | 看实体风险列表 | 该实体风险分=Σ×新权重 | 未配置实体→默认 Medium×1 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 资产关键度 CRUD(按 IP/用户/主机) | P2 | type ∈ {ip,user,host};级别→权重映射 Low 0.5 / Medium 1 / High 1.5 / Extreme 2;文件存权重数值、UI 存级别,换算关系写死在 `CriticalityService` 一处 |
| FR-2 | 保存并触发实体风险重算 | P2 | 保存成功后调用 `entity-risk.py --write` 一次(或标记 affected 实体待重算);**不是"保存即可见"**,风险分在重算后刷新(见 §6 链路) |
| FR-3 | 检索资产 | P2 | 按 IP/名称前缀模糊 |
| FR-4 | 批量导入(CSV:type,key,level) | P2 | 逐行校验,非法行跳过并报告;`overwrite=false` 时重复 key 默认跳过、可改覆盖 |
| FR-5 | 格式校验 | P2 | IP(IPv4/IPv6)、主机名(RFC 1123)严格校验;级别枚举校验;通配前缀(如 `10.0.0.*`)输入允许、但 MVP 聚合仅精确匹配(P2 加前缀匹配) |
| FR-6 | 默认值策略 | P2 | 未配置的实体按 Medium×1(权重 1.0)参与聚合,与 entity-risk.py 现状一致 |
| FR-7 | 幂等保存 | P2 | 重复 PUT 相同 (type,key,level) → 200 成功,不报错、不产生变化 |
| FR-8 | 操作审计 | P2 | 记录谁在何时改了什么资产的关键度(含 before/after 权重、操作类型),可追溯 |
| FR-9 | host 仅存储、不参与聚合 | P2 | MVP:host 可增删改存文件,但 `entity-risk.py` 不对 host 加权(与现状一致);后续接口变更见 §9 |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 资产列表加载 P95 <500ms(≥1000 条);批量导入 ≤10s;entity-risk.py 加载含 1000+ 条关键度的 JSON 无性能问题、整表重算数秒级 |
| 权限/安全 | 级别↔权重换算唯一写死在 `CriticalityService`,防 UI 层伪造权重;写操作记录 operator + time + 变更字段(before/after 权重),审计可追溯;敏感字段不返回 |
| 异常恢复/回滚 | 写 `asset-criticality.json` 先写临时文件再 rename 原子替换,不产生半写文件;写失败保留旧文件、前端提示失败;重算失败保留旧风险分、可从 §5.2 recompute 手动重跑;重复 PUT 同值→幂等 200 不报错 |
| 可观测 | 重算任务有日志与最近执行时间(lastRunAt/status);失败可重试;批量导入结果(imported/skipped/errors)逐行可查 |
| 可维护性 | 配置即代码:文件唯一事实来源 + Git 版本化/变更历史/review(检测即代码,01 F-R10);console 编辑即写 repo 文件 |

## 5. 后端架构

```
前端(设置) → Spring Boot API(/api/settings/criticality) → 写 infra/elasticsearch/asset-criticality.json
                                                                    │
                                                                    └→ deploy.sh rsync → entity-risk.py --write(重算)→ siem-entity-risk
```

### 5.1 组件与职责
| 组件 | 职责 |
| --- | --- |
| `CriticalityService`(新) | 资产关键度 CRUD/批量导入,读写 JSON;**级别↔权重换算唯一写死处**(Low 0.5 / Medium 1 / High 1.5 / Extreme 2) |
| `CriticalityValidator`(新) | IP(IPv4/IPv6)/主机名(RFC 1123)/级别枚举校验,解析 CSV |
| `RecomputeTask`(新) | 保存成功后异步调用 `entity-risk.py --write`(幂等);失败可重试 |
| 存储 `asset-criticality.json`(已有) | 权重数值(`{ip,user,host}` 三映射,与现状 2.0 一致);`_comment` 注释键写回时保留 |
| `entity-risk.py`(已有) | 读 JSON,对 source.ip/user.name 加权聚合(host 不聚合,见 §9 决策) |

> **级别↔权重换算**:UI 下拉与批量导入均传**级别字符串**,`CriticalityService` 内的常量表 `{Low:0.5, Medium:1.0, High:1.5, Extreme:2.0}` 是唯一换算点;文件只落权重数值。

### 5.2 API 契约
```
type ∈ {ip, user, host};level ∈ {Low, Medium, High, Extreme}

GET    /api/settings/criticality                                  → 200 全量 {ip:{key:{level,weight}}, user:{}, host:{}}(服务端统一换算,前端不再换算)
PUT    /api/settings/criticality/{type}/{key}                     body {"level":"Extreme"} → 200 {type,key,level,weight};幂等(重复同值仍 200)
DELETE /api/settings/criticality/{type}/{key}                     → 204;key 不存在 → 404
POST   /api/settings/criticality/batch                             body {items:[{type,key,level}], overwrite:boolean} → 201 {imported, updated, skipped, errors:[{line,type,key,reason}]}
GET    /api/settings/criticality/recompute                         → 200 {lastRunAt, status}(重算状态,只读)
POST   /api/settings/criticality/recompute                         → 202 {taskId}(手动触发重算)
```

**GET 响应样例**(读回时由 `CriticalityService` 将权重换算回 level 一并返回,level+weight 双字段;前端不做换算,文件仍只存权重数值):
```json
{
  "ip":    {"10.0.0.1": {"level": "Extreme", "weight": 2.0}},
  "user":  {"root": {"level": "High", "weight": 1.5}},
  "host":  {"server01": {"level": "Extreme", "weight": 2.0}}
}
```

**PUT 请求/响应样例**(为 10.0.0.1 设 Extreme):
```json
// 请求
PUT /api/settings/criticality/ip/10.0.0.1
{ "level": "Extreme" }

// 响应 200
{ "type": "ip", "key": "10.0.0.1", "level": "Extreme", "weight": 2.0 }
```

**批量导入请求/响应样例**:
```json
// 请求(overwrite=false:重复 key 跳过并计入 skipped)
POST /api/settings/criticality/batch
{ "items": [ {"type": "ip",   "key": "172.16.1.20", "level": "High"},
             {"type": "host", "key": "dc-01",       "level": "Extreme"},
             {"type": "user", "key": "bad..key",    "level": "Low"} ],
  "overwrite": false }

// 响应 201
{ "imported": 2, "updated": 0, "skipped": 0,
  "errors": [ {"line": 3, "type": "user", "key": "bad..key", "reason": "用户名非法:含连续点"} ] }
```

**4xx 错误码**:
| 码 | 场景 |
| --- | --- |
| 400 | type/level 非枚举;key 格式非法(IP 不是合法 IPv4/IPv6、主机名违反 RFC 1123、key 为空);批量请求 JSON 结构非法 |
| 404 | DELETE/重算查询中 type/key 不存在 |

### 5.3 存储
| 数据 | 存储 | 说明 |
| --- | --- | --- |
| 资产关键度 | `infra/elasticsearch/asset-criticality.json` | 文件 + Git(检测即代码,单一来源);值存权重数值;console 编辑即写 repo 文件,Git 提供变更历史 |
| 审计记录 | `siem-audit-*`(ES)或 console 侧日志 | MVP 至少落日志;有登录上下文后记录 actor |
| 重算状态 | console 侧(lastRunAt/status) | 供 §5.2 recompute 查询 |

### 5.4 配置同步与生效链路(强制)

> 资产关键度写 `infra/elasticsearch/asset-criticality.json`,属「写 repo 文件后需生效」的功能,必须填本节;通用链路与 deploy.sh 一致,禁止另起通道。

| 配置对象 | 写入位置 | 校验 | 生效动作 | 失败回滚 |
| --- | --- | --- | --- | --- |
| 资产关键度 | `infra/elasticsearch/asset-criticality.json` | JSON schema 校验 + 级别枚举(asset.criticality)/权重范围校验(服务端,见 §5.1 `CriticalityValidator`) | deploy.sh rsync → `entity-risk.py --write` 重算(整表,幂等 `_id=type-value`) | 写/rsync 失败→保留旧文件、状态=failed 可重试;重算失败→旧风险分保留、§5.2 recompute 手动重跑 |

## 6. 数据流实现

```
① 设置页 → CRUD/batch API → 校验 → 写 repo infra/elasticsearch/asset-criticality.json(权重数值)
② deploy.sh rsync 同步文件到 WSL
③ 保存成功 → 触发 entity-risk.py --write(幂等,_id=type-value 覆盖)→ 重算 siem-entity-risk
④ 控制台/Kibana 读 siem-entity-risk → 显示实体风险分 = Σalert.risk_score × 权重
```

> **保存→生效不是即时的**:保存只更新 JSON 文件,**可见性取决于一次成功的重算**。MVP 触发方式 = 保存成功后调用 `entity-risk.py --write` 一次(整表重算,幂等、数秒级);或由 `RecomputeTask` 标记 affected 实体、定时 job 重算(alert-service 迁移后并入其调度)。重算失败 → 旧风险分保留,可从 §5.2 recompute 手动重跑。
> **路径**:仓库相对路径 `infra/elasticsearch/asset-criticality.json`(WSL 侧 `/mnt/d/Project/SIEM/infra/elasticsearch/`),`entity-risk.py` 同目录按绝对路径读取。

| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |
| 编辑 | 前端表单/CSV(type,key,level) | 格式+枚举校验;级别→权重换算(唯一换算点);重复 key 弹窗覆盖/合并 | 内存态改动(未落盘) | 参数非法→400 逐行原因;重复且不覆盖→201 计入 skipped |
| 保存 | 内存态改动 | 写 repo `infra/elasticsearch/asset-criticality.json`(ip/user/host 三映射权重数值,保留 `_comment`;先写临时文件再 rename 原子替换) | 文件更新 | 写失败→回滚不破坏旧文件、前端提示失败;重复 PUT 同值→幂等 200 |
| 同步 | 更新后的 JSON | deploy.sh rsync → WSL 侧文件覆盖 | WSL 文件就位 | rsync 失败→保留旧文件、状态=failed 可重试 |
| 重算 | 保存成功事件 | `entity-risk.py --write` 读 JSON,按 source.ip/user.name 精确匹配加权,重写 siem-entity-risk(_id=type-value 幂等) | 实体风险分=round(Σalert.risk_score×权重, 1) | 重算失败→旧分保留、日志可查、手动重跑;缺文件→按空字典默认 1.0 |
| 呈现 | siem-entity-risk | 控制台/Kibana 查询 | 风险分/风险等级 | 无实体→空态 |

## 7. 验收标准(DoD)

- **正常**:**Given** `10.0.0.1` 近 30d 告警 risk_score 之和为 S(如 S=35.0)且当前未配置(默认权重 1.0)**When** 设置页将其设为 Extreme 并保存,随后触发实体风险重算 **Then** `siem-entity-risk` 中 `entity.type=ip, entity.value=10.0.0.1` 的 `risk_score` 变为 `round(S×2, 1)`(即 S×2,权重精确 2.0)。
- **正常**:**Given** 批量导入含 3 行 (type,key,level) 且格式全部合法 **When** 上传 CSV **Then** 返回 imported=3,列表新增 3 条。
- **异常**:**Given** 保存时 JSON 写入失败(如目录只读)**When** 提交 **Then** 前端提示失败,`asset-criticality.json` 内容不破坏(旧文件保留)。
- **异常**:**Given** 批量 CSV 含非法行(type=user, key=`bad..key`)**When** 导入 **Then** 该行跳过并报 reason,其余合法行正常导入(errors 逐行列出)。
- **边界**:**Given** 为已存在的 key 再次 PUT 相同 level **When** 提交 **Then** 返回 200、无变化、幂等不报错(符合 FR-7)。
- **边界**:**Given** 批量导入 `overwrite=false` 且 CSV 含重复 key **When** 提交 **Then** 返回 201,重复行计入 skipped(不返回 409),前端提示"已存在,可改覆盖"。
- **边界**:**Given** 未配置任何资产的实体 **When** 重算 **Then** 按默认 Medium×1 加权(权重 1.0),与 entity-risk.py 兜底一致。
- **边界(宿主决)**:**Given** 为 `server01`(host)设 Extreme **When** 保存并重算 **Then** host 仅存文件、不参与聚合(`siem-entity-risk` 无 host 实体),符合 MVP 决策。
- **性能**:**Given** 资产清单 ≥1000 条 **When** 打开设置页加载列表 **Then** <1s 返回;`entity-risk.py` 重算正常完成。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [Elastic 实体风险评分](https://www.elastic.co/docs/solutions/security/advanced-entity-analytics/entity-risk-scoring) | 资产关键度权重模型 |

## 9. 开放问题

> 存储选型 / 生效机制已收敛为 §10 决定(ADR-1/ADR-2);以下仅留真正未决。历史已收敛记录保留供追溯。

- ~~host 是否参与聚合?~~ → **已决定**:MVP host 仅存储、暂不参与聚合(entity-risk.py 只对 source.ip/user.name 加权,与现状一致);未来给 entity-risk.py 加 host 聚合(按 `host.name` 分组 + 加权)属接口变更,另行排期。
- ~~关键度默认值与批量导入?~~ → **已决定**:未配置默认 Medium×1(权重 1.0);批量导入(CSV:type,key,level)入 FR-4。
- **仍开放**:
  - 通配关键度(如 `10.0.0.*` / `*.corp.local`)是否进 P2 前缀匹配;MVP 仅精确匹配(避免误伤相邻网段)。
  - 保存→重算的触发调度:MVP 为"保存成功后调用一次";是否引入定时重算(如每小时)统一兜底,待 alert-service 迁移时定。
  - 审计落点:ES `siem-audit-*` vs console 日志文件;控制台无鉴权时 actor 记为 system/console。

## 10. 设计决策(ADR 式)

### ADR-1 [资产关键度存储选型:文件 + Git,不做 ES 索引/关系库]
- **背景**:关键度要可版本化、可 review、可回滚;数据量小(≤千级),由 `entity-risk.py` 全量读取,无高频写与跨索引关联需求。
- **选项**:A. `infra/elasticsearch/asset-criticality.json` 文件 + Git / B. ES 索引 `siem-criticality` / C. 关系库。
- **取舍**:A 与 entity-risk.py 现有读取路径零改动、Git 天然提供 review/历史/回滚(检测即代码,01 F-R10);B 引入写入/同步/权限开销,重算前仍需落文件,对 ≤千级数据无收益;C 增加跨系统一致性成本。
- **决定**:采用 **A. `infra/elasticsearch/asset-criticality.json`(文件 + Git)**,单一事实来源,值存权重数值(§5.3 已标注「已有」)。

### ADR-2 [生效机制:保存 → rsync → entity-risk.py 重算]
- **背景**:保存 JSON ≠ 立即生效——新权重要等一次重算才反映到 `siem-entity-risk` 风险分。
- **选项**:A. 保存成功后立即整表重算(`entity-risk.py --write` 一次,幂等 `_id=type-value`)/ B. 定时重算兜底 / C. 仅写文件不重算。
- **取舍**:A 反馈及时(整表重算数秒级)、幂等可重跑,MVP 首选;定时重算作为统一兜底待 alert-service 迁移时定(§9);C 导致「保存不可见」,不可接受。
- **决定**:采用 **A**(保存成功后调用 `entity-risk.py --write` 一次);失败回滚口径与 §5.4 一致——写/rsync 失败保留旧文件、状态=failed 可重试;重算失败保留旧风险分,可从 §5.2 recompute 手动重跑。
