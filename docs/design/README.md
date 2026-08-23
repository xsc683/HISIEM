# docs/design — 专项技术参考

这里不再存放 Phase 3/4 的阶段任务、目标架构或整套产品设计。那些文档已经与当前实现重复或产生过时接口，已整合到 [`../architecture.md`](../architecture.md)、[`../product-contract.md`](../product-contract.md) 和 [`../roadmap.md`](../roadmap.md) 后删除。

保留的文档必须满足“有独立技术价值、能从代码/配置验证、不会充当当前总览”的条件：

| 文档 | 定位 | 状态规则 |
| --- | --- | --- |
| [mitre-coverage.md](mitre-coverage.md) | 当前检测规则的 ATT&CK 覆盖矩阵 | 规则变化时同步 |
| [ocsf-mapping.md](ocsf-mapping.md) | ECS 之外的最小 OCSF 辅助视图 | 明确区分已落地和设计值 |
| [security-rbac.md](security-rbac.md) | ES/Kafka 生产安全加固参考 | 未执行的步骤不是当前能力 |
| [threat-intel.md](threat-intel.md) | 本地 TI 字典富化实现和升级边界 | 以 `infra/ti` 与 Logstash 配置为准 |
| [soar-playbook-mvp.md](soar-playbook-mvp.md) | SOAR 画布 Start/Action/Condition/End 与 Handle 连线基线 | 与 Vue Flow 编辑器和后端图校验同步 |

## 使用边界

- 当前架构、数据流和模块边界：看 [`../architecture.md`](../architecture.md)。
- 当前页面、API、用户旅程和验收：看 [`../product-contract.md`](../product-contract.md)。
- 当前完成度、运行态和生产风险：看 [`../current-status.md`](../current-status.md)。
- 组件命令和故障处理：看 [`../operations.md`](../operations.md)；不要从专项设计复制旧部署命令。

专项文档中的“待实现”“后置”“设计值”必须在 [`../roadmap.md`](../roadmap.md) 有对应条目；否则应删除，而不是继续扩展成另一套产品计划。
