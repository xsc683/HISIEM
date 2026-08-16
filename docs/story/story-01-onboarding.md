# Story 01 — 数据源接入向导

> **元信息**
> - 关联模块:08 产品设计 §5.1 接入中心(深化见 [07](../design/07-product-design.md))
> - 优先级:**MVP**
> - 状态:草稿
> - 依赖:无(前后端骨架已就绪,见 b2051fd)

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 接入一个新日志源时能用向导完成"选模板 → 配端点 → 发样例测试 → 生效",
**以便** 不用手改 Logstash 配置就能让日志流入并参与检测。

## 2. 背景与目标

### 2.1 背景(当前痛点)
- 当前加数据源 = 手改 `infra/logstash/pipeline/logstash.conf`(grok/ECS/时间解析),门槛高、不可复用、无测试。
- 已有前后端骨架:模板列表、解析测试(java-grok)、配置预览、前端日志接入页——但**数据源只预览配置,没落库、没生效到 Logstash**。

### 2.2 目标(可度量)
- 数据源接入从"改配置"变为"≤10 分钟向导完成并真正生效"。
- 数据源声明可落库、可管理(列表/状态),不再是临时预览。

### 2.3 非目标
- 不做用户权限/多租户。
- 不做采集代理(Agent)部署(当前 tcp/syslog 输入即可)。

## 3. 用户旅程

```
① 新建数据源 → ② 选解析模板 → ③ 配采集端点 → ④ 发样例测试 → ⑤ 生效 → ⑥ 跳数据健康
```

| 步骤 | 用户操作 | 界面反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ① | 接入中心"新建数据源" | 向导第一步 | — |
| ② | 从模板库选模板(如 SSH 认证) | 显示模板说明/协议 | 库为空→提示先建模板 |
| ③ | 填名称、端口/路径 | 校验端口占用 | 端口冲突→红提示 |
| ④ | 粘贴样例日志点"测试" | 字段预览 + 成功/失败 | 解析失败→提示换模板/自定义 |
| ⑤ | 点"生效" | 生成配置 + 同步状态 | 同步失败→显示错误日志 |
| ⑥ | 跳转到该源健康页 | 最近 1h 事件量 | 无数据→提示检查采集 |

## 4. 需求明细

### 4.1 功能需求

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 数据源声明落库(名称/协议/模板/端点/状态) | MVP | 存 ES `siem-log-sources` 或 YAML |
| FR-2 | 由模板生成**完整** Logstash 配置(input+filter+output) | MVP | 扩展现有 preview 为完整 pipeline |
| FR-3 | 生效:配置同步到 Logstash 并生效 | MVP | 走 deploy.sh 同步 + 重启/热加载 |
| FR-4 | 数据源列表(状态:创建中/已生效/停用/失败) | MVP | 接入中心"我的数据源" |
| FR-5 | 接入后跳转数据健康页 | MVP | 见 Story 05 |

### 4.2 非功能需求

| 维度 | 要求 |
| --- | --- |
| 性能 | 解析测试 <1s;配置生成即时 |
| 可观测 | 生效过程有日志;失败可排查 |
| 安全 | 数据源配置不暴露密码类敏感信息 |

## 5. 后端架构

```
前端(React 接入向导) → Spring Boot API(/api/log-sources) → 存储(ES siem-log-sources)
                             │
                             └→ LogstashConfigGenerator → 完整 pipeline 片段 → deploy.sh 同步 → Logstash
```

### 5.1 组件与职责
| 组件 | 职责 |
| --- | --- |
| `LogSourceService`(新) | 数据源 CRUD、落库、状态机 |
| `LogstashConfigGenerator`(扩展) | 由模板生成完整 input+filter+output |
| `ParserTemplateService`(已有) | 模板加载 |
| 存储 `siem-log-sources` | 数据源声明(名称/协议/模板/端口/状态) |

### 5.2 API 契约
```
GET    /api/log-sources              → 200 [{id,name,protocol,templateId,port,status}]
POST   /api/log-sources              → 201 {id}(创建,落库)
POST   /api/log-sources/{id}/activate → 200 {config, syncStatus}(生成配置 + 触发同步)
POST   /api/log-sources/{id}/test    → 复用解析测试
DELETE /api/log-sources/{id}         → 204
```

### 5.3 存储
| 数据 | 存储 | 说明 |
| --- | --- | --- |
| 数据源声明 | ES `siem-log-sources` | 或 YAML 文件(起步),二者择一 |
| 解析模板 | `infra/parser-templates/*.yaml` | 已有 |

## 6. 数据流实现

```
① 前端向导 → POST /api/log-sources(落库)→ 返回 id
② 点"生效" → POST /api/log-sources/{id}/activate → 生成完整 Logstash 配置
③ 配置同步:deploy.sh 把生成片段写入 ~/projects/mini-siem/logstash/pipeline → 重启 logstash
④ 日志源 → 新 input 端口 → Logstash 解析(模板)→ Kafka → Flink → ES 事件/告警
⑤ 数据健康页:按 source 聚合事件量/失败率验证
```

| 环节 | 输入 | 处理 | 输出 | 异常处理 |
| --- | --- | --- | --- | --- |
| 声明 | 前端表单 | 校验+落库 | 数据源记录 | 端口冲突/参数非法→4xx |
| 生效 | activate | 生成配置+同步 | 生效状态 | 同步失败→状态=失败,可重试 |
| 采集 | 日志流 | Logstash 模板解析 | 事件→Kafka/ES | 解析失败→_parsefailure 计数 |

## 7. 验收标准(DoD)

- **正常**:**Given** 管理员在接入中心新建数据源并选 SSH 模板、配端口 5001 **When** 粘贴一条 SSH 日志点"测试"并点"生效" **Then** 配置生成并同步,日志从 5001 流入,数据健康页显示事件量增长。
- **异常**:**Given** 数据源端口已被占用 **When** 提交 **Then** 前端红提示端口冲突,不落库。
- **边界**:**Given** 样例日志不匹配任何 grok 模式 **When** 点"测试" **Then** 显示"解析失败",提示换模板/自定义解析,不进入生效。

## 8. 业界参考 / 最佳实践

| 参考 | 借鉴 |
| --- | --- |
| [Splunk Add Data 向导](https://docs.splunk.com/Documentation/Splunk/9.1.8/Admin/Propsconf) | 向导式接入:选类型→配置→识别 |
| [QRadar Log Source 管理](https://community.ibm.com/community/user/blogs/kajal-sangani/2023/10/16/dsm-parsing-in-qradar) | 日志源管理 + 自动识别 |
| [Cribl Onboarding](https://cribl.io/blog/why-having-an-onboarding-process-matters/) | 样例测试 + 数据源文档化 + 健康 |

## 9. 开放问题

- 数据源存储用 ES 索引还是 YAML 文件?(MVP 倾向 YAML + Git,复用模板模式)
- 生效机制:deploy.sh 全量同步 vs Logstash 热加载/多 pipeline?(见技术设计 06)
