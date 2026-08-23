# 当前状态与交付基线

> 定位：这是项目当前事实的单一入口。内容以代码、`infra/` 配置和最近一次可复现验证为准；详细方案、阶段记录和验收用例分别见设计文档与 Story 文档。
>
> 基线日期：2026-08-23（WSL2 + Docker Desktop）

## 一句话结论

HISIEM 已完成检测链路、控制面、接入向导、告警处置、调查台、SOAR 人工触发/审批和运行态扫描的主要闭环，可以作为开发/演示环境使用；生产级部署仍需完成安全加固、高可用和跨存储一致性治理。

## 已验证能力

| 领域 | 当前结论 | 事实来源 |
| --- | --- | --- |
| 数据链路 | Logstash → Elasticsearch/Kafka → Flink → 告警索引链路可运行 | [架构](architecture.md)、`infra/` |
| 控制面 | Spring Boot + PostgreSQL/Flyway，认证、RBAC、案件、审计、通知和后台任务可用 | [部署](deployment.md)、[路线图](roadmap.md) |
| 前端 | React/Vite 控制台可构建并访问控制面 API | `web/`、[当前产品契约](product-contract.md) |
| 运行态 | PostgreSQL、Elasticsearch、Kafka、Logstash、Flink、Kibana 均有健康扫描 | [运维手册](operations.md) |
| SOAR | 告警/案件 Playbook、条件、审批、执行快照、步骤记录和失败重试可用 | [SOAR 设计](soar.md)、`infra/soar/playbooks/` |
| 自动化验证 | 根项目 81 个测试、Flink 33 个测试、前端生产构建通过 | [路线图](roadmap.md) |
| 备份恢复 | ES 临时索引备份恢复演练通过 | `infra/elasticsearch/backup-restore-rehearsal.sh` |

## 当前部署基线

- 编排文件：`infra/docker-compose.yml`，固定 Compose 项目名为 `infra`。
- Kafka：内部客户端使用 `kafka:9092`，宿主机验证入口使用 `localhost:9092`；`siem-events` 已按检测作业要求保留 3 个分区。
- Logstash：容器内监控 API 在 `127.0.0.1:9600`，宿主机扫描显示 `UP / degraded TCP` 时，只代表端口监听，需按[运维手册](operations.md)进入容器确认 pipeline。
- 数据源配置：`infra/log-sources/` 与 `infra/logstash/pipeline/log-sources/` 是可审计的项目配置；生成或修改配置必须走控制面接口或部署脚本，不能直接改运行容器。

## 已闭环的重点问题

- 动态数据源解析失败写入 raw 索引，避免进入正常事件索引和检测链路。
- DataHealth 同时统计正常事件、失败事件以及配置中的数据源。
- 案件、告警、规则、审计和后台任务已迁移到控制面持久化，并补充租约/恢复器。
- 告警 sink 使用保护分析师处置字段的 partial update。
- 数据源生命周期按源串行，配置文件使用原子替换，文件输入使用持久 sincedb。
- 前端统一处理 204、非 JSON 错误、初始化失败、轮询超时和破坏性操作确认。
- Kafka、Flink、Logstash 的健康探针区分“真正健康”和“仅端口可达”，避免把降级结果误报为完整健康。

## 仍需解决的生产风险

这些事项不阻塞开发环境使用，但不能标记为生产级已完成：

1. Elasticsearch/Kafka 默认仍是单节点、明文和低副本配置，需按部署环境启用认证、TLS、持久化和 RF≥2。
2. Case 的 PostgreSQL 事实源与 Elasticsearch 镜像依赖 outbox/重放，仍需持续演练断点、重试和告警清理。
3. 后台任务已有租约和启动恢复，但具体 handler 的自动重放、幂等键和跨实例协调仍需补齐。
4. 尚未实现租户字段、索引隔离和文档级权限；当前模型是单租户。
5. 真实生产负载下的容量、保留策略、升级回滚和灾备 RTO/RPO 还需要环境级压测与演练。
6. SOAR 当前仅支持人工触发和内部白名单动作；自动触发、长任务 worker、连接器凭据/出口控制和全局熔断尚未完成。

## 文档使用规则

- “现在是什么”：先看本页、[架构](architecture.md)和[运维手册](operations.md)。
- “怎么部署”：看[部署指南](deployment.md)；不要从 Story 或学习文档复制部署命令。
- “为什么这样设计”：看[设计决策](design-decisions.md)和 `docs/design/`。
- “怎么验收一个功能”：看[当前产品契约](product-contract.md)；它是当前验收契约，不复制历史 Story 长文。
- “怎么学习组件”：看 `docs/learn/`；学习文档允许保留简化示例，不替代生产配置。
- “历史审计证据”：看 [`archive/architecture-audit-2026-08.md`](archive/architecture-audit-2026-08.md)。它保留分析过程和风险证据，不作为日常入口。

状态有变化时，先更新本页、[路线图](roadmap.md)和[当前产品契约](product-contract.md)，避免在多个模块文档中维护互相矛盾的状态表。
