# Phase 3 设计 — 需求分析

> 状态:设计稿 · 2026-08-11
> 本文档基于「成熟商业 SIEM 对标(Splunk ES / IBM QRadar / MS Sentinel / Elastic Security / Google SecOps / Wazuh)+ ES/Logstash/Kafka/Flink 组件最佳实践」研究产出,是 Phase 3 系统化设计的**需求层**。架构与落地见同目录 `02-architecture.md` / `03-component-best-practices.md` / `04-detection-engineering.md`,执行顺序见 `05-roadmap.md`。

## 1. 背景与目标

当前系统已完成 Phase 1(管道 MVP)+ Phase 2(ECS schema、规则引擎、多规则、时间窗口关联、Kibana),端到端链路验证通过。下一步目标是:**对照业界成熟 SIEM 的能力模型,把"能跑通"升级为"符合业界标准做法的 SIEM"**,重点解决当前最突出的三个问题:

1. **告警疲劳**:当前 3 条单事件规则是"1 事件 1 告警",无去重/抑制/评分,告警会淹没分析师(业界数据:62% SOC 告警被直接无视)。
2. **可靠性边界**:Flink checkpoint 无持久化目录、ES sink 无确定性 `_id`(重启重放会重复告警)、窗口 watermark 无 idle 处理、无 ILM 留存策略。
3. **检测工程化缺失**:规则是硬编码 Java 对象,无 MITRE ATT&CK 标注、无数值风险分、无 status/版本/测试驱动,难以演进与度量覆盖。

## 2. 现状盘点(Phase 1+2 已实现)

| 层 | 现状 | 局限 |
| --- | --- | --- |
| 采集 | Logstash 单 pipeline,TCP :5000 | 单数据源(SSH);无 persistent queue / DLQ;stdout rubydebug 未关 |
| 解析 | Grok + ECS 字段标准化;`@timestamp`=日志时间(date filter,Asia/Shanghai) | `Failed password for invalid user` 变体漏解析;失败行静默漏过 |
| 缓冲 | Kafka 3.8 单 topic `siem-events`,1 分区,JSON | 无压缩、无显式 retention;1 分区锁死消费并行;单 broker 无容错 |
| 检测 | Flink 2.1 单 job:3 单事件规则 + 1 窗口规则(事件时间 tumbling 5min≥5) | 无 CEP 序列关联;无去重抑制;无幂等 `_id`;checkpoint 未持久化;无 `.uid()`;窗口无 idle 处理 |
| 存储 | ES 8.14:`siem-events-*` 按天 + `siem-alerts`(模板+显式 mapping) | 单节点 replica=1(yellow);无 ILM 留存;无压缩/refresh 调优;无 RBAC |
| 呈现 | Kibana `SIEM 总览` dashboard | 无告警三线视图/调优闭环;无覆盖度视图 |

## 3. 业界对标(参考模型)

### 3.1 能力域模型

成熟 SIEM 的能力收敛为 **10 大能力域**(Splunk ES / QRadar / Sentinel / Elastic / SecOps 跨平台实测归纳):

1. 采集与连接器
2. 数据归一化
3. 检测与关联
4. 威胁情报富化
5. 风险告警(评分/分级)
6. 合规报表
7. UEBA / 异常检测
8. 调查 / 案件管理
9. SOAR / playbook 自动化
10. 部署弹性(云/自托管/SaaS)

### 3.2 三代演进与我们的定位

| 代际 | 特征 | 本项目的阶段 |
| --- | --- | --- |
| 1 代 | 被动日志聚合 + 签名关联 | ~Phase 1 |
| 2 代 | 规则/分析引擎驱动 + 合规报表 | **~Phase 2-3(当前)** |
| 3 代 | 云原生流式引擎 + UEBA/AI 评分 | 远期目标(明确后置) |

我们应扎实做好 2 代核心(检测/关联/告警管理/留存),3 代重能力(UEBA/ML、SOAR、网络流分析)明确砍掉。

### 3.3 归一化是 SIEM 灵魂

- 业界正从厂商绑定模型(CIM / ECS / ASIM / UDM)向**开放的 OCSF** 收敛:一次归一化 → 多工具可移植,规则/看板未来换平台不必重写。
- **本项目已做对一半**:存储 schema 用 ECS(点分、`source.ip`/`@timestamp`),是 Elastic 生态事实标准,方向正确。缺的是**可移植层**——补一套 OCSF 字段映射,保留未来对接 AWS Security Lake / 换平台的能力。

### 3.4 检测与关联四层(业界成熟分层)

| 层 | 手段 | 解决什么 | 本项目现状 |
| --- | --- | --- | --- |
| ① 规则检测 | 时间/阈值/模式规则 | 已知攻击特征 | ✅ 3+1 条规则 |
| ② 窗口关联 | 滑动/滚动窗口,5-60min 关联窗口 | 事件时间因果、高频行为 | ⚠️ 仅 1 条 tumbling 窗口 |
| ③ 基线/异常 | μ+3σ、z-score、peer group | 未知/零日/内鬼 | ❌ 后置(需 30-90 天学习期) |
| ④ 攻击链关联 | 序列关联(CEP),弱信号一致性评分 | 多阶段攻击叙事 | ❌ P1 引入 CEP |

> 关键结论(业界):**关联是 80% 领域知识 + 20% ML,确定性规则必须先行**。

## 4. 差距分析(当前 → 目标)

| 能力 | 现状 | 目标 | 优先级 |
| --- | --- | --- | --- |
| 告警去重/抑制 | 无(1 事件 1 告警) | 规则级 suppression(按实体+时间窗口) | **P0** |
| 风险评分 | 无(静态 severity 字符串) | 数值 risk_score + 实体级聚合(复刻 Splunk RBA) | **P0** |
| 规则元数据 | 仅 id/name/type/severity/desc | + MITRE tags / risk_score / status / version / references | **P0** |
| 告警幂等 | ES sink 无 `_id` | 确定性 `_id`(重放变 upsert) | **P0** |
| checkpoint 持久化 | 容器本地盘,重启即丢 | 挂载持久卷 + 显式 restart strategy | **P0** |
| 窗口 idle 处理 | 无(日志暂停窗口不关) | `.withIdleness(60s)` | P1 |
| 攻击链关联 | 无 | CEP 序列规则(失败→成功登录) | P1 |
| OCSF 可移植层 | 无 | OCSF 字段映射 | P1 |
| 告警生命周期 | 无 status/verdict | status + verdict + Kibana 三线 + 误报回流 | P1 |
| ILM 留存 | 无(按天裸索引) | delete 阶段 ILM(hot/warm/delete) | P0 |
| GeoIP 富化 | 无 | Logstash geoip filter(at-ingest) | P2 |
| 基线异常 | 无 | Flink 统计 job(滚动基线) | P2 |
| 检测即代码 | 硬编码规则 | 规则 YAML + 测试夹具 + CI | P2 |

## 5. 功能需求(按能力域,MoSCoW)

### 5.1 Must(Phase 3.0 — 可靠性基线)

- **F-R1 告警幂等**:告警写入 ES 用确定性 `_id = hash(rule_id + 实体 + 时间桶)`,重启/重放不产生重复告警。
- **F-R2 持久化状态**:Flink checkpoint/savepoint 落到持久卷;显式 EXACTLY_ONCE、restart-strategy、timeout。
- **F-R3 留存策略**:`siem-events-*` / `siem-alerts` 挂 ILM 生命周期(hot 7d → delete 90d 等,数值可调)。
- **F-R4 单节点健康**:replica=0(消除 yellow 与写放大)、refresh_interval/压缩/translog 调优。
- **F-R5 规则元数据**:Rule 增加 MITRE tags、risk_score、status、version、references;告警输出 `rule.tags`/`alert.risk_score`。

### 5.2 Must(Phase 3.1 — 减噪)

- **F-R6 告警抑制**:单事件规则加 suppression(按 rule_id+source.ip+user.name,TTRL 状态去重,默认 15-60min)。
- **F-R7 风险评分**:每条规则定数值 risk_score;Kibana 按 risk_score DESC 排序,实体风险聚合(alert-service 定时 job)。

### 5.3 Should(Phase 3.2 — 检测工程化)

- **F-R8 攻击链关联**:CEP 序列规则(例:同源 10min 内"多次认证失败 → 随后成功登录"= T1110/T1078)。
- **F-R9 OCSF 映射层**:输出侧 OCSF 视图(class_uid/severity_id 等)。
- **F-R10 检测即代码**:规则 YAML(Sigma 风格)+ 正负测试夹具 + lint + CI。

### 5.4 Should(Phase 3.3 — 告警闭环与富化)

- **F-R11 告警生命周期**:`alert.status`(open/acknowledged/closed)+ `alert.analyst_verdict`(true_positive/false_positive/duplicate),Kibana 三线视图,verdict 回流调优。
- **F-R12 GeoIP 富化**:Logstash geoip filter 加 `source.geo.*`(at-ingest,近零成本)。

### 5.5 Could(远期)

- **F-R13 基线异常**:Flink 滚动基线 job(30 天)μ+3σ 告警,仅高频信号。
- **F-R14 威胁情报**:TI feed(STIX/TAXII 或 AbuseIPDB CSV)查表富化。
- **F-R15 资产关键度权重**:asset-criticality 表(0.5/1/1.5/2)加权风险分。

## 6. 非功能需求

| 维度 | 需求 | 依据 |
| --- | --- | --- |
| 性能 | 单机支撑 ≥100 EPS 稳定摄入与实时检测(当前 SSH 单源远低于此,留足余量) | 单机 lab 定位 |
| 可用性 | Logstash 崩溃时 tcp 输入事件不丢(persistent queue);Kafka 侧可容忍在途窗口丢失(已双写 ES) | PQ + 双写边界 |
| 数据一致性 | 告警 at-least-once + 幂等 `_id`(不重复);事件 at-least-once | ES8 sink 语义 |
| 可扩展 | Kafka 分区 1→3、Logstash 可拆多 pipeline、Flink 算子可扩并行;保留横向路径 | 预留,不提前做 |
| 安全 | 单机 PLAINTEXT 为显式决策并文档化;暴露网络前启用 basic auth + RBAC(最小权限) | 见 03 文档 |
| 合规(远期) | PCI DSS v4.0 留存 ≥12 个月/近 3 月可查;留存策略书面化 | 见 04 文档 |
| 可运维 | 监控:ES 健康/分片、Kafka lag、Flink checkpoint 时延、Logstash flow 指标 | 见 03 文档 |

## 7. 明确不做(范围边界)

- ❌ 完整 UEBA / ML 平台(无 ML 基础设施,基线异常仅对高频信号做统计版)
- ❌ 网络流分析(NetFlow/sFlow,需额外采集链)
- ❌ SOAR 编排引擎(TheHive/Shuffle 等,告警→事件聚合放 alert-service 即可)
- ❌ 合规报表包(PCI/HIPAA/SOC2 模板)
- ❌ 独立威胁情报平台(以轻量查表/feed 起步)
- ❌ Schema Registry 引入(单生产者+单消费者+ECS+ES 模板已足够,出现第二生产者/消费者再评估)

## 8. 验收口径

- **可靠性**:重启 Flink job(从持久化 checkpoint 恢复)后,重复发同一批日志,`siem-alerts` 不出现重复告警(幂等 `_id` 验证)。
- **减噪**:同一实体同规则短时间重复命中,只产生 1 条告警(合并 event_count)。
- **留存**:`GET _cat/indices` 能看到 ILM 策略生效;超过保留期的索引被自动删除。
- **可度量**:Kibana 能按 `alert.risk_score` 排序、按规则看告警量/误报率;ATT&CK 覆盖矩阵可见。
- **检测能力**:新增的攻击链 CEP 规则能对"失败→成功"序列产生单条告警。
