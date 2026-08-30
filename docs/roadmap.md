# 项目路线图与完成基线

> 定位：统一收纳阶段计划、已交付能力和后续优先级。旧阶段稿已删除；本页是唯一的路线图入口，日常只以本页的状态为准。遗留问题 ID、影响和可验证关闭条件见[项目进展与遗留问题](project-progress.md)。

## 已交付阶段

| 阶段 | 主题 | 状态 | 说明 |
| --- | --- | --- | --- |
| 3.0–3.5 | 检测引擎基线 | 已完成 | 事件标准化、Kafka/Flink、单事件/窗口/CEP/基线规则、告警 Schema；详见[架构](architecture.md)和[规则引擎](rule-engine.md) |
| 4.0 | 业务正确性 | 已完成 | DataHealth、告警/案件状态机、重复归属校验 |
| 4.1 | 控制面持久化 | 已完成 | PostgreSQL/Flyway、认证、案件、通知、审计、后台任务 |
| 4.2 | 安全与工程化 | 已完成 | Spring Security、会话持久化、失败限制、Actuator、任务查询 |
| 4.3 | 产品与运维 | 已完成 | 数据源回滚、案件元数据、健康扫描、备份恢复演练 |
| 4.4 | 能力补全 | 已完成 | checkpoint、数据源接入、通知治理、资产关键度、调查台 |
| 4.4.1 | 可靠性与用户旅程收口 | 已完成 | 脱敏、强制改密、租约恢复、partial update、并发配置、前端错误处理、健康语义 |
| 4.5 | 旧 SOAR 原型 | 已替换 | V8-V10 迁移保留历史，YAML/Connector/复杂图运行时代码已移除 |
| 4.6 | SOAR 生命周期 MVP 重做 | 已完成 | V11 独立表、alert/case lifecycle Kafka、六类 DAG 节点、字段/动作字典、持久审批/等待、节点 I/O 和 Vue Flow |
| 4.7 | SOAR Handler 执行内核 | 已完成 | V12 显式 ExecutionContext、Handler Registry、统一 NodeResult、Kafka trigger envelope、逐 attempt 历史、指数退避和业务动作幂等回执 |
| 4.8 | Vue 3 控制台与规则编写 | 已完成 | vue-router 深链、模块化列表/表单/详情、规则 DSL CRUD、结构化告警/案件和 Vue Flow Handle 连线 |
| 4.9 | 可靠性回归门禁 | 已完成 | Case 删除镜像状态、keystore 隔离、SOAR fencing/续租、编辑器离开保存、Flink 解析 DLQ、GitHub Actions 与 Playwright E2E |
| 4.10 | SOAR 持久能力扩展 | 已完成 | V13–V15 持久 Parallel/Join、静态 item Loop、取消/失败传播、手动触发、Connector SPI/HTTP、验证器链和设计器表单 |
| 5A/5B | Managed Detection Runtime | 已完成（单集群 process path） | V17/V18 desired/observed、durable lease/fencing、独立 controller、immutable job-group artifact、structured Flink identity、真实 job/artifact observation、启动校验和 opt-in process/disabled adapter；生产 HA、多集群编排和灾备治理仍未完成 |

## 当前验收基线

每次发布候选版本至少执行：

```bash
./mvnw.cmd test
./mvnw.cmd -f flink/pom.xml test
npm.cmd --prefix web test
npm.cmd --prefix web run build
npm.cmd --prefix web run test:e2e
```

当前 Maven 基线为 control-api 149 个测试、detection-runtime 21 个测试、detection-controller 17 个测试、SOAR worker 1 个测试、Flink 46 个测试，全部通过；前端生产构建和浏览器 E2E 的最近结果仍以 CI/历史验证记录为准，测试数量随新增回归用例变化，最终以 Maven/Node/Playwright 输出为准。涉及基础设施时还要执行 Docker Compose、健康扫描、Kafka/Flink/lifecycle 链路和 ES 备份恢复验证。结果与环境说明集中记录在[当前状态](current-status.md)和[运维手册](operations.md)。

## 下一阶段优先级

### P0：生产安全门禁

- Elasticsearch/Kafka 认证与 TLS；多节点和 RF≥2；密钥、快照和最小权限策略。
- 首次部署改密、密码轮换、会话失效和审计字段的持续回归。

### P1：一致性与可恢复性

- Case outbox 的重试、幂等、告警清理和断点演练。
- 后台 task handler 自动重放、幂等键、跨实例租约和指标告警。
- Flink 告警 partial update 的并发回归，以及事件/告警/案件的端到端测试。

### P2：容量与产品边界

- 真实负载下的分区、checkpoint、索引生命周期、保留策略和 RTO/RPO 压测。
- 多租户字段、索引隔离、文档级权限和更细粒度的角色模型。
- 通知渠道、更多接入协议和可视化信息架构的持续评估。
- SOAR lifecycle 事务 outbox/DLQ、OR 条件、动态 map/while、子 Playbook、Connector 凭据/mTLS/代理/隔离、AI Agent，以及跨地域容量验证。

## 学习路线

学习任务不再和产品阶段混写：按 [`learn/README.md`](learn/README.md) 的“概念 → 小实验 → 项目改造 → 测试 → 部署 → 复盘”推进。每个改造任务应注明它是项目修复、学习实践或两者兼有。

## 变更规则

阶段完成后更新本页的状态和验收命令；设计文档记录决策与细节，Story 文档记录用户旅程与验收，运行态结果记录在[当前状态](current-status.md)。不要在每个模块文档重复维护一份独立的“当前完成度”表。
