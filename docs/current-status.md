# 当前状态与交付基线

> 定位：这是项目当前事实的单一入口。内容以代码、`infra/` 配置和最近一次可复现验证为准；详细方案、阶段记录和验收用例分别见设计文档与 Story 文档。
>
> 基线日期：2026-08-24（WSL2 + Docker Desktop）

## 一句话结论

HISIEM 已完成检测链路、控制面、接入向导、告警处置、调查台、生命周期驱动的 SOAR MVP 和运行态扫描主要闭环，可以作为开发/演示环境使用；生产级部署仍需完成数据面安全加固、高可用和跨存储一致性治理。

## 已验证能力

| 领域 | 当前结论 | 事实来源 |
| --- | --- | --- |
| 数据链路 | Logstash → Elasticsearch/Kafka → Flink → 告警索引链路可运行；Flink 解析毒消息进入独立 Kafka DLQ | [架构](architecture.md)、`infra/` |
| 控制面 | Spring Boot + PostgreSQL/Flyway，认证、RBAC、案件、审计、通知和后台任务可用 | [部署](deployment.md)、[路线图](roadmap.md) |
| 前端 | Vue 3/Vite + vue-router + Ant Design Vue 控制台；统一色彩、排版、间距、控件状态和页面/卡片/筛选/表格视觉壳，租户与账户操作收纳在侧栏底部；桌面侧栏与移动抽屉、响应式表单/表格、结构化加载/空/错误状态、受控 ES 日志检索、安全运营大屏、Kibana 入口、深链详情与 Vue Flow SOAR 画布可构建 | `web/`、[当前产品契约](product-contract.md) |
| 运行态 | PostgreSQL、Elasticsearch、Kafka、Logstash、Flink、Kibana 均有健康扫描 | [运维手册](operations.md) |
| SOAR | lifecycle + 手动入口、11 类节点、持久 Parallel/Join 与 Loop、Connector SPI/HTTP、验证器链、节点 I/O、消息去重、租约续期/fencing 和 Vue Flow 编辑器可用 | [SOAR 设计](soar.md)、`src/main/java/**/soar/` |
| 自动化验证 | 根项目 124 个测试、Flink 38 个测试、前端 9 个单元测试、生产构建和 3 条 Playwright E2E 通过；包含真实 PostgreSQL V15、日志查询约束/竞态、运营聚合、fencing/续租、并行/循环、Flink DLQ 与离开保存 | [路线图](roadmap.md) |
| 备份恢复 | ES 临时索引备份恢复演练通过 | `infra/elasticsearch/backup-restore-rehearsal.sh` |

## 当前部署基线

- 编排文件：`infra/docker-compose.yml`，固定 Compose 项目名为 `infra`。
- Kafka：内部客户端使用 `kafka:9092`，宿主机验证入口使用 `localhost:9092`；`siem-events`、`siem-events-dlq`、`siem-alert-lifecycle`、`siem-case-lifecycle` 均配置为 3 个分区。
- Elasticsearch keystore 是部署环境的敏感运行态文件：Git 明确忽略，`deploy.sh` 同步配置时也不会覆盖目标环境 keystore。
- Logstash：容器内监控 API 在 `127.0.0.1:9600`，宿主机扫描显示 `UP / degraded TCP` 时，只代表端口监听，需按[运维手册](operations.md)进入容器确认 pipeline。
- 数据源配置：`infra/log-sources/` 与 `infra/logstash/pipeline/log-sources/` 是可审计的项目配置；生成或修改配置必须走控制面接口或部署脚本，不能直接改运行容器。

## 已闭环的重点问题

- 动态数据源解析失败写入 raw 索引，避免进入正常事件索引和检测链路。
- DataHealth 同时统计正常事件、失败事件以及配置中的数据源。
- 案件、告警、规则、审计和后台任务已迁移到控制面持久化，并补充租约/恢复器。
- 告警 sink 使用保护分析师处置字段的 partial update。
- 数据源生命周期按源串行，配置文件使用原子替换，文件输入使用持久 sincedb。
- 前端统一处理 204、非 JSON 错误、初始化失败、轮询超时和破坏性操作确认；按路由加载模块，不在根组件拉取全站数据。
- 日志检索只允许后端字段目录与字段实际支持的 8 类结构化关系，显式排除 raw 索引；旧请求不会覆盖新结果。运营大屏使用告警/案件全量状态聚合与最新时间序列展示库存和闭环指标，并在标签页隐藏时暂停 10 秒轮询。
- 检测规则支持结构化逻辑展示、single_event/window 创建编辑、YAML 原子写入、审计和显式部署；告警与案件使用独立详情路由。
- Kafka、Flink、Logstash 的健康探针区分“真正健康”和“仅端口可达”，避免把降级结果误报为完整健康。
- Case 镜像删除把任意 2xx 和 404 视为幂等成功；SOAR 状态提交校验 owner、fencing token 与未过期租约，长节点执行时持续续租。
- Playbook 路由离开会等待最新草稿保存；保存失败会阻止导航，浏览器刷新/关闭时对未保存内容给出原生确认。
- Flink 对坏 JSON、缺失或非法 `@timestamp` 使用 side output 写入 `siem-events-dlq`，不再用处理时间掩盖事件时间错误或让作业反复重启。

## 仍需解决的生产风险

这些事项不阻塞开发环境使用，但不能标记为生产级已完成：

1. Elasticsearch/Kafka 默认仍是单节点、明文和低副本配置，需按部署环境启用认证、TLS、持久化和 RF≥2。
2. Case 的 PostgreSQL 事实源与 Elasticsearch 镜像依赖 outbox/重放，仍需持续演练断点、重试和告警清理。
3. 后台任务已有租约和启动恢复，但具体 handler 的自动重放、幂等键和跨实例协调仍需补齐。
4. SOAR 控制面按 tenant 隔离 Playbook/执行/审批；告警、案件和 ES 数据面仍缺少完整 tenant 字段、索引隔离和文档级权限。
5. 真实生产负载下的容量、保留策略、升级回滚和灾备 RTO/RPO 还需要环境级压测与演练。
6. SOAR 控制面发布 lifecycle 消息还不是事务 outbox；事件解析已有 Flink DLQ topic，但 lifecycle 消息仍缺少可运营的 DLQ/重放。持久并行/静态循环、手动触发和通用 HTTP Connector 基线已实现；生产仍缺凭据治理、mTLS/出口代理、限流/熔断/隔离、子流程、AI、长时间压测与跨地域恢复演练。

## 文档使用规则

- “现在是什么”：先看本页、[架构](architecture.md)和[运维手册](operations.md)。
- “怎么部署”：看[部署指南](deployment.md)；不要从 Story 或学习文档复制部署命令。
- “为什么这样设计”：看[设计决策](design-decisions.md)和 `docs/design/`。
- “怎么验收一个功能”：看[当前产品契约](product-contract.md)；它是当前验收契约，不复制历史 Story 长文。
- “怎么学习组件”：看 `docs/learn/`；学习文档允许保留简化示例，不替代生产配置。
- “历史审计证据”：看 [`archive/architecture-audit-2026-08.md`](archive/architecture-audit-2026-08.md)。它保留分析过程和风险证据，不作为日常入口。

状态有变化时，先更新本页、[路线图](roadmap.md)和[当前产品契约](product-contract.md)，再同步派生的[项目进展与遗留问题](project-progress.md)，避免在多个模块文档中维护互相矛盾的状态表。
