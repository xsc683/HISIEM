# Phase 3 设计 — 实施路线图

> 状态:已实现基线见各阶段状态列 · 2026-08-16(Phase 3.0–3.3 已全部实现;B1 排序清单/FP 率视图/cancel→restore 演练 于 2026-08-16 补齐;规则 YAML 化 + 规则 lint 门禁(RuleLintTest)+ ES snapshot 恢复演练 于 2026-08-16 落地;剩余待做:TI 富化;3.4/3.5 部分落地;commit 7e86478 / b284fa3 / da1a0f6 / 6524fb6 / c6fb407)
> 分阶段落地顺序。每阶段独立可验收、可交付,依赖前置阶段。优先级符号:P0=立即 / P1=近期 / P2=计划 / P3=远期。

---

## 总览

| 阶段 | 主题 | 核心目标 | 主要依赖 | 状态 |
| --- | --- | --- | --- | --- |
| 3.0 | 可靠性基线 | 告警不重、状态不丢、留存生效 | 无 | ✅ 完成(.uid 全算子 + cancel→restore 演练,2026-08-16) |
| 3.1 | 减噪 | 抑制 + 风险评分 | 3.0(幂等/schema) | 🟡 部分(抑制 ✅ b284fa3;risk_score 排序清单待做) |
| 3.2 | 检测工程化 | 规则元数据 + 攻击链 CEP + 检测即代码 | 3.1 | 🟡 部分(CEP/OCSF/元数据 ✅ da1a0f6;规则 YAML/lint/CI 待做) |
| 3.3 | 告警闭环与富化 | 三线流转 + verdict 回流 + GeoIP | 3.2 | ✅ 完成(三线/verdict/GeoIP + 按规则 FP 率视图,2026-08-16) |
| 3.4 | 合规与归档 | ILM 长留存 + snapshot | 3.0 | 🟡 部分(ILM/backup/RBAC ✅ 6524fb6,其余待做) |
| 3.5 | 智能化 | 基线异常 / 实体风险聚合 | 3.3 | 🟡 部分(基线/实体风险 ✅ c6fb407,威胁情报待做) |

---

## Phase 3.0 — 可靠性基线(先做,全部 P0)

**目标**:消除"单机不重启"的隐性假设——告警幂等、状态持久化、留存策略、单节点健康。

| 落地项 | 文档来源 | 验收 | 状态 |
| --- | --- | --- | --- |
| Flink checkpoint/savepoint 持久卷 + 显式 EXACTLY_ONCE/timeout/restart-strategy | 03-F3/F1 | 重启 job 后从 checkpoint 恢复 | ✅ 已实现(7e86478) |
| ES sink 确定性 `_id`(幂等 upsert) | 03-F2 | 重放同一批日志不产生重复告警 | ✅ 已实现(7e86478) |
| 全算子 `.uid()` | 03-F4 | 算子具备确定性 uid | ✅ 已实现(7e86478) |
| cancel→restore 演练 | 03-F4 | savepoint 恢复成功 | ✅ 已做(2026-08-16:从 savepoint-c4f1c3-b08014e830a6 恢复,RUNNING 且检测正常、无重放重复) |
| ES 模板 replica=0 + ILM delete 策略 + refresh/压缩 | 03-E1/E2/E3 | 无 yellow;`_ilm/explain` 显示策略 | ✅ 已实现(7e86478) |
| Logstash PQ + 去 stdout + LS_HEAP + invalid-user grok | 03-L1/L2/L3/L4 | 崩溃窗口不丢;`_parsefailure` 可查 | ✅ 已实现(7e86478) |
| Kafka 分区 1→3 + zstd + acks=all | 03-K1/K2 | 3 分区;lag 稳定 | ✅ 已实现(7e86478) |
| 规则元数据(MITRE/risk_score/status)落 Rule + 告警 | 04-§2 | 告警含 `rule.tags`/`alert.risk_score` | ✅ 已实现(7e86478) |

**验收口径(已满足)**:重复发同一批日志,`siem-alerts` 数量不变(幂等)。Given-When-Then 示例:
- **Given** `infra/simulator/brute-force-test.sh` 连发同一批日志,记录 `siem-alerts/_count` = N。
- **When** `flink cancel <JobID>` 取消 job,再从 checkpoint 恢复重启,重新连发同一批日志。
- **Then** `siem-alerts/_count` 仍为 N,不产生重复告警。

---

## Phase 3.1 — 减噪(告警疲劳)

**目标**:每事件一条告警 → 实体级抑制 + 风险分排序。

| 落地项 | 文档来源 | 优先级 | 状态 |
| --- | --- | --- | --- |
| 单事件规则 suppression(keyed state,处理时间对齐 60min 桶) | 03-F6 / 04-§4.1 | P0 | ✅ 已实现(b284fa3) |
| Kibana 告警清单按 `alert.risk_score` DESC | 03-B1 | P0 | ✅ 已做(2026-08-16:vis-alerts-risk 表格,规则按 risk_score DESC) |
| watermark `.withIdleness(60s)` | 03-F5 | P1 | ✅ 已实现(b284fa3) |
| Kafka retention 3d + check-lag.sh | 03-K3/K4 | P1 | ✅ 已实现(b284fa3) |
| DLQ 启用 | 03-L5 | P1 | ✅ 已实现(b284fa3) |
| Logstash pipelines.yml + ecs_compatibility v8 | 03-L6 | P1 | ✅ 已实现(b284fa3) |

**验收口径(已满足)**:同实体同规则同一处理时间对齐 60min 桶内重复命中 → 1 条告警,`alert.deduplicated_count` 累加(首个命中即发 count=1,桶内后续命中不新建)。已知边界:跨桶(如 11:59:59 与 12:00:01)不抑制,属权衡;「首次命中时间起算的滑动抑制(TTL)」列为 P1 备选。

---

## Phase 3.2 — 检测工程化

**目标**:规则从硬编码升级为可度量、可测试、可演进的资产。

| 落地项 | 文档来源 | 优先级 | 状态 |
| --- | --- | --- | --- |
| CEP 序列规则(失败→成功登录,rule-ssh-bruteforce-success-001) | 04-§1 / 03-F8 | P1 | ✅ 已实现(da1a0f6) |
| OCSF 映射层 | 02-§5.3 | P1 | ✅ 已实现(da1a0f6) |
| 规则 YAML(Sigma 风格)+ 转换器 | 04-§3 | P2 | ⏳ 待做(da1a0f6 只加 CEP+OCSF+EventConditionsTest;规则 YAML 声明/转换器待做) |
| 正负夹具测试 + lint + CI | 04-§6 | P2 | ⏳ 待做(CEP/基线正负夹具测试 ✅;lint + CI 待做) |
| ATT&CK 覆盖矩阵/Navigator layer JSON | 04-§2 | P2 | ✅ 已实现(da1a0f6) |

**验收口径(已满足)**:新增"暴力破解→成功登录"规则对攻击序列产生单条告警;`mvn test` 跑规则回归。

---

## Phase 3.3 — 告警闭环与富化

**目标**:从"告警列表"到"可调查的告警生命周期"。

| 落地项 | 文档来源 | 优先级 | 状态 |
| --- | --- | --- | --- |
| `alert.status` / `alert.analyst_verdict` + Kibana 三线 | 04-§4.3 / 03-B2 | P1 | ✅ 已实现(6524fb6) |
| GeoIP 富化(at-ingest) | 03-L7 / 04-§5 | P2 | ✅ 已实现(6524fb6,事件侧) |
| 按规则 FP 率视图 | 03-B4 | P1 | ✅ 已做(2026-08-16:vis-fp-rate 表格,FP/(TP+FP) 按规则,不含 duplicate) |

**验收口径(已满足)**:Kibana 三线视图 + triage-alert.py 完成 5 态流转({open, acknowledged, investigating, resolved, closed},核心 open→ack→closed)并强制 verdict;`source.geo.*` 出现在事件(事件侧已实现;告警侧不富化 GeoIP,如需告警侧富化另列落地项)。

---

## Phase 3.4 — 合规与归档

**目标**:满足留存策略与归档需求。

| 落地项 | 文档来源 | 优先级 | 验收 | 状态 |
| --- | --- | --- | --- | --- |
| ILM 长留存(hot→delete 365d,无 warm)与留存策略文档化 | 04-§7 | P3 | `_ilm/explain` 显示 hot→delete 365d 策略(无 warm) | ✅ 已实现(6524fb6) |
| snapshot repository + delete 前快照(backup.sh) | 03-E6 | P2 | backup.sh 可执行创建快照 | ✅ 已实现(6524fb6) |
| ES basic auth + RBAC(最小权限) | 03-E5 | P2 | 非 admin 用户仅读 siem-* | ✅ 已实现(6524fb6) |
| 合规 dashboard(留存/归档视图) | — | P3 | 留存/归档状态可视化 | ⏳ 待做 |
| snapshot 恢复演练 | 03-E6 | P3 | 从快照恢复索引 | ✅ 已做(2026-08-16:siem-backups 仓库注册 + siem-drill 快照 → 恢复到 restored_* 计数一致 47=47,已清理) |

---

## Phase 3.5 — 智能化(基线异常/实体风险已落地,威胁情报后置)

| 落地项 | 文档来源 | 优先级 | 验收 | 状态 |
| --- | --- | --- | --- | --- |
| 基线异常 Flink 统计 job(滚动 24h,可配 baselineHours;μ+3σ,BaselineAnomalyFunction) | 01-F-R13 | P2 | 基线偏离 μ+3σ 触发 rule-auth-rate-anomaly-001 | ✅ 已实现(c6fb407) |
| 实体风险聚合(entity-risk.py,资产权重加权;alert-service 为未来迁移目标) | 04-§4.2 | P2 | siem-entity-risk 索引产出实体风险分 | ✅ 已实现(c6fb407) |
| 威胁情报查表富化 | 01-F-R14 | P2 | — | ⏳ 待做 |

---

## 明确不做(维持 01-§7 范围)

UEBA/ML 平台、网络流分析、SOAR 编排引擎、合规报表包、独立威胁情报平台、Schema Registry。

## 执行建议

1. **从 3.0 开始,按阶段顺序推进**,每阶段结束跑 01-§8 的验收口径。
2. **一次只改一个参数**(尤其 Flink/Logstash 调优),观察 10 分钟再继续(业界 1.5-2x 步进原则)。
3. 每个落地项在对应 `infra/` README 记录**为什么这么配**(决策上下文),防止后人照搬生产集群配置。
4. 规则演进走"检测即代码"路径后,新增规则以 PR + 测试夹具方式合入。

## Phase 4 与 story 衔接

Phase 4(用户接入层)的排期与 Story 映射见 [docs/story/README.md](../story/README.md) 的「Phase ↔ Story 关联矩阵」小节:4.0 接入 MVP=story-01(02 可选)、4.1=story-02/05、4.2=story-03/04/09、4.3=story-06/08/10、远期=story-07。Phase 3.x 为已实现的检测引擎代码(无 story)。
