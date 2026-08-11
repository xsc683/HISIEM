# Phase 3 设计 — 实施路线图

> 状态:设计稿 · 2026-08-11
> 分阶段落地顺序。每阶段独立可验收、可交付,依赖前置阶段。优先级符号:P0=立即 / P1=近期 / P2=计划 / P3=远期。

---

## 总览

| 阶段 | 主题 | 核心目标 | 主要依赖 |
| --- | --- | --- | --- |
| 3.0 | 可靠性基线 | 告警不重、状态不丢、留存生效 | 无 |
| 3.1 | 减噪 | 抑制 + 风险评分 | 3.0(幂等/schema) |
| 3.2 | 检测工程化 | 规则元数据 + 攻击链 CEP + 检测即代码 | 3.1 |
| 3.3 | 告警闭环与富化 | 三线流转 + verdict 回流 + GeoIP | 3.2 |
| 3.4 | 合规与归档 | ILM 长留存 + snapshot | 3.0 |
| 3.5 | 智能化(远期) | 基线异常 / 实体风险聚合 | 3.3 |

---

## Phase 3.0 — 可靠性基线(先做,全部 P0)

**目标**:消除"单机不重启"的隐性假设——告警幂等、状态持久化、留存策略、单节点健康。

| 落地项 | 文档来源 | 验收 |
| --- | --- | --- |
| Flink checkpoint/savepoint 持久卷 + 显式 EXACTLY_ONCE/timeout/restart-strategy | 03-F3/F1 | 重启 job 后从 checkpoint 恢复 |
| ES sink 确定性 `_id`(幂等 upsert) | 03-F2 | 重放同一批日志不产生重复告警 |
| 全算子 `.uid()` + cancel→restore 演练 | 03-F4 | savepoint 恢复成功 |
| ES 模板 replica=0 + ILM delete 策略 + refresh/压缩 | 03-E1/E2/E3 | 无 yellow;`_ilm/explain` 显示策略 |
| Logstash PQ + 去 stdout + LS_HEAP + invalid-user grok | 03-L1/L2/L3/L4 | 崩溃窗口不丢;`_parsefailure` 可查 |
| Kafka 分区 1→3 + zstd + acks=all | 03-K1/K2 | 3 分区;lag 稳定 |
| 规则元数据(MITRE/risk_score/status)落 Rule + 告警 | 04-§2 | 告警含 `rule.tags`/`alert.risk_score` |

**验收口径**:重新部署后,重复发同一批日志,`siem-alerts` 数量不变(幂等)。

---

## Phase 3.1 — 减噪(告警疲劳)

**目标**:每事件一条告警 → 实体级抑制 + 风险分排序。

| 落地项 | 文档来源 | 优先级 |
| --- | --- | --- |
| 单事件规则 suppression(keyed state+TTL) | 03-F6 / 04-§4.1 | P0 |
| Kibana 告警清单按 `alert.risk_score` DESC | 03-B1 | P0 |
| watermark `.withIdleness(60s)` | 03-F5 | P1 |
| Kafka retention 3d + check-lag.sh | 03-K3/K4 | P1 |
| DLQ 启用 | 03-L5 | P1 |
| Logstash pipelines.yml + ecs_compatibility v8 | 03-L6 | P1 |

**验收口径**:同实体同规则 1 小时内重复命中 → 1 条告警,`alert.deduplicated_count` 累加。

---

## Phase 3.2 — 检测工程化

**目标**:规则从硬编码升级为可度量、可测试、可演进的资产。

| 落地项 | 文档来源 | 优先级 |
| --- | --- | --- |
| CEP 序列规则(失败→成功登录) | 04-§1 / 03-F8 | P1 |
| OCSF 映射层 | 02-§5.3 | P1 |
| 规则 YAML(Sigma 风格)+ 转换器 | 04-§3 | P2 |
| 正负夹具测试 + lint + CI | 04-§6 | P2 |
| ATT&CK 覆盖矩阵/Navigator layer JSON | 04-§2 | P2 |

**验收口径**:新增"暴力破解→成功登录"规则对攻击序列产生单条告警;`mvn test` 跑规则回归。

---

## Phase 3.3 — 告警闭环与富化

**目标**:从"告警列表"到"可调查的告警生命周期"。

| 落地项 | 文档来源 | 优先级 |
| --- | --- | --- |
| `alert.status` / `alert.analyst_verdict` + Kibana 三线 | 04-§4.3 / 03-B2 | P1 |
| GeoIP 富化(at-ingest) | 03-L7 / 04-§5 | P2 |
| 按规则 FP 率视图 | 03-B4 | P1 |

**验收口径**:Kibana 能完成 open→ack→closed 流转并强制 verdict;`source.geo.*` 出现在事件/告警。

---

## Phase 3.4 — 合规与归档

**目标**:满足留存策略与归档需求。

| 落地项 | 文档来源 | 优先级 |
| --- | --- | --- |
| ILM 长留存(hot/warm/delete)与留存策略文档化 | 04-§7 | P3 |
| snapshot repository + delete 前快照 | 03-E6 | P2 |
| ES basic auth + RBAC(最小权限) | 03-E5 | P2 |

---

## Phase 3.5 — 智能化(远期,明确后置)

| 落地项 | 文档来源 | 优先级 |
| --- | --- | --- |
| 基线异常 Flink 统计 job(滚动 30 天 μ+3σ) | 01-F-R13 | P2 |
| 实体风险聚合(alert-service 定时 job,资产权重加权) | 04-§4.2 | P2 |
| 威胁情报查表富化 | 01-F-R14 | P2 |

---

## 明确不做(维持 01-§7 范围)

UEBA/ML 平台、网络流分析、SOAR 编排引擎、合规报表包、独立威胁情报平台、Schema Registry。

## 执行建议

1. **从 3.0 开始,按阶段顺序推进**,每阶段结束跑 01-§8 的验收口径。
2. **一次只改一个参数**(尤其 Flink/Logstash 调优),观察 10 分钟再继续(业界 1.5-2x 步进原则)。
3. 每个落地项在对应 `infra/` README 记录**为什么这么配**(决策上下文),防止后人照搬生产集群配置。
4. 规则演进走"检测即代码"路径后,新增规则以 PR + 测试夹具方式合入。
