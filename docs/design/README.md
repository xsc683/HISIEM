# docs/design — 专项技术参考

这里不再存放 Phase 3/4 的阶段任务、目标架构或整套产品设计。那些文档已经与当前实现重复或产生过时接口，已整合到 [`../architecture.md`](../architecture.md)、[`../product-contract.md`](../product-contract.md) 和 [`../roadmap.md`](../roadmap.md) 后删除。

保留的文档必须满足“有独立技术价值、能从代码/配置验证、不会充当当前总览”的条件。下表覆盖本目录当前全部 10 篇专项文档，另有 1 篇阶段任务清单已归档（见文末）：

| 文档 | 定位 | 状态规则 |
| --- | --- | --- |
| [module-boundaries.md](module-boundaries.md) | Maven 模块依赖、进程角色与隔离规则 | 模块拆分或进程角色变化时同步；与 `CLAUDE.md`、[`../current-status.md`](../current-status.md) 保持一致 |
| [managed-detection-runtime.md](managed-detection-runtime.md) | detection controller 的 claim/lease/fencing、immutable artifact、Flink process adapter、real observed state、adapter 模式与 5B 限制 | Phase 5A/5B 的已实现/待实现必须逐段标注；与 `modules/detection-runtime`、`applications/detection-controller` 同步 |
| [soar-runtime-architecture.md](soar-runtime-architecture.md) | 从事实落库、Kafka 消费到租约 Worker、节点推进、挂起恢复和一致性保护的完整执行链 | 以 `modules/soar-core`、`soar-worker-runtime` 代码为准 |
| [soar-capability-runtime.md](soar-capability-runtime.md) | 持久并行/循环、手动触发、Connector 与验证器链的数据流和边界 | 与 `soar-core` 引擎和 Connector SPI 同步 |
| [soar-playbook-mvp.md](soar-playbook-mvp.md) | SOAR 画布 Start/Action/Condition/End 与 Handle 连线基线 | 与 Vue Flow 编辑器和后端图校验同步 |
| [mitre-coverage.md](mitre-coverage.md) | 当前检测规则的 ATT&CK 覆盖矩阵 | 规则变化时同步 |
| [ocsf-mapping.md](ocsf-mapping.md) | ECS 之外的最小 OCSF 辅助视图 | 明确区分已落地和设计值 |
| [security-rbac.md](security-rbac.md) | ES/Kafka 生产安全加固参考 | 未执行的步骤不是当前能力 |
| [threat-intel.md](threat-intel.md) | 本地 TI 字典富化实现和升级边界 | 以 `infra/ti` 与 Logstash 配置为准 |
| [copilot-workspace-ux-brief.md](copilot-workspace-ux-brief.md) | HISIEM-SOC-Copilot Stage D 调查工作台的实现级 UX brief（跨仓参考，权威在 Copilot 仓 `docs/stage-contracts/`） | 只描述增量补齐；与 Copilot 仓契约和本仓已有实现同步 |

已归档：[`project-task-status.md`](../archive/project-task-status.md)——跨仓阶段任务清单，按 [`../README.md`](../README.md) 规则 2 归档（阶段计划只写入 [`../roadmap.md`](../roadmap.md)）。

## 使用边界

- 当前架构、数据流和模块边界：看 [`../architecture.md`](../architecture.md)。
- 当前页面、API、用户旅程和验收：看 [`../product-contract.md`](../product-contract.md)。
- 当前完成度、运行态和生产风险：看 [`../current-status.md`](../current-status.md)。
- 组件命令和故障处理：看 [`../operations.md`](../operations.md)；不要从专项设计复制旧部署命令。

专项文档中的“待实现”“后置”“设计值”必须在 [`../roadmap.md`](../roadmap.md) 有对应条目；否则应删除，而不是继续扩展成另一套产品计划。
