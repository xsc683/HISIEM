# Phase 3 设计 — 需求分析

> 状态:设计稿 · Phase 3.0-3.5 检测基线与 Phase 4.0-4.3 控制面能力已实现;后续能力仍按本文优先级管理
> 本文档基于「成熟商业 SIEM 对标(Splunk ES / IBM QRadar / MS Sentinel / Elastic Security / Google SecOps / Wazuh)+ ES/Logstash/Kafka/Flink 组件最佳实践」研究产出,是 Phase 3 系统化设计的**需求层**。架构与落地见同目录 `02-architecture.md` / `03-component-best-practices.md` / `04-detection-engineering.md`,执行顺序见 `05-roadmap.md`。

## 1. 背景与目标

当前系统的数据面已完成 Phase 1/2 管道与 Phase 3.0-3.5 检测基线，Phase 4.0-4.3 已补齐控制台、PostgreSQL 控制面、安全和运维闭环，端到端链路验证通过。下一步目标是:**对照业界成熟 SIEM 的能力模型,把"能跑通"继续升级为"符合业界标准做法的 SIEM"**,重点解决当前最突出的三个问题:

1. **告警疲劳**:早期单事件规则是"1 事件 1 告警",告警会淹没分析师(业界数据:62% SOC 告警被直接无视);Phase 3 已通过告警抑制/风险评分/实体聚合减噪(见 §2.1),当前共 6 条规则。
2. **可靠性边界**:Flink checkpoint 无持久化目录、ES sink 无确定性 `_id`(重启重放会重复告警)、窗口 watermark 无 idle 处理、无 ILM 留存策略 → 均已解决(见 §2.1)。
3. **检测工程化缺失**:规则早期硬编码 Java 对象;Phase 3 已补 MITRE ATT&CK 标注(`rule.tags`/`attack.*` 全小写)、`rule.status` 与数值风险分(`alert.risk_score`)、规则 YAML 声明化与 lint 门禁(f1739e0/4c74f35);剩余缺口:rule.version 在窗口/CEP/基线的覆盖 + references 待补。

## 2. 现状盘点(数据面与控制面基线已实现)

> Phase 3.0-3.5(2026-08-16)已把下表「局限」列的主要缺口落地,明细见 §2.1「已实现基线」。

| 层 | 现状 | 局限 |
| --- | --- | --- |
| 采集 | Logstash 单 pipeline,TCP :5000 | 早期单数据源(SSH)、无 PQ/DLQ → persistent queue + DLQ 已落地(Phase 3.1 `b284fa3`);多数据源由接入模块扩展(story-01) |
| 解析 | Grok + ECS 字段标准化;`@timestamp`=日志时间(date filter,Asia/Shanghai) | `Failed password for invalid user` 变体漏解析;失败行静默漏过 |
| 缓冲 | Kafka 3.8 单 topic `siem-events`,3 分区,zstd 压缩,acks=all | 早期 1 分区/无压缩 → 已扩 3 分区 + zstd + acks=all(Phase 3.0 `7e86478`);显式 retention 未设;单 broker 无容错 |
| 检测 | Flink 单 job:6 条规则(3 单事件 + 1 窗口 tumbling 5min≥5 + 1 CEP 攻击链 + 1 基线异常) | 早期:无 CEP 序列关联、无去重抑制、无幂等 `_id`、checkpoint 未持久化、无 `.uid()`、窗口无 idle 处理 → 均已由 Phase 3.0-3.5 解决(见 §2.1) |
| 存储 | ES 8.14:`siem-events-*` 按天 + `siem-alerts`(模板+显式 mapping) | 早期 replica=1(yellow)、无 ILM 留存 → 已解决:replica=0 + ILM(hot → delete 365d,无 warm);无压缩/refresh 调优;无 RBAC |
| 呈现 | Kibana `SIEM 总览` dashboard | 早期无告警三线视图/调优闭环、无覆盖度视图 → 已落地:triage-alert.py 三线处置(Phase 3.3 `6524fb6`)+ `rule.tags`/`attack.*` 覆盖度可见(Phase 3.2 `da1a0f6`) |

### 2.1 已实现基线(2026-08-16,Phase 3.0-3.5)

Phase 3.0-3.5 检测引擎能力已全部实现并验证(commit `7e86478` / `b284fa3` / `da1a0f6` / `6524fb6` / `c6fb407`,2026-08-16)。下表逐项对应上表「局限」列:

| 项 | 落地内容(实现口径) | 归属 |
| --- | --- | --- |
| 告警幂等 | `DetectionJob.alertId()` 确定性 `_id`(重放变 upsert,不重复告警) | Phase 3.0 `7e86478` |
| 全算子 `.uid()` | source / parser / detection / suppression / window / cep / anomaly / sink 均设 uid | Phase 3.0 `7e86478` |
| checkpoint 持久化 | `enableCheckpointing(60s, EXACTLY_ONCE)` + 持久卷 + 显式 restart 策略 | Phase 3.0 `7e86478` |
| Kafka 3 分区 | `siem-events` 3 分区 + zstd 压缩 + acks=all | Phase 3.0 `7e86478` |
| replica=0 | 单节点 `number_of_replicas: 0`,消除 yellow | Phase 3.0 `7e86478` |
| ILM 留存 | `siem-events-*`:hot → delete `min_age 365d`(无 warm,单节点;满足 PCI 12 个月留存) | Phase 3.0 `7e86478` / 3.4 `6524fb6` |
| PQ + DLQ | Logstash persistent queue + dead letter queue(ES 拒收事件落 DLQ);`siem-events-raw` 未知桶 P2 再落地 | Phase 3.1 `b284fa3` |
| watermark idle | 窗口 / CEP / 基线均 `.withIdleness(60s)` | Phase 3.1 `b284fa3` |
| 告警抑制 | `AlertSuppressor`:keyBy(rule_id + 实体(source.ip/user.name)) + 处理时间 60min 对齐桶 + onTimer 产最终告警;首命中即发 count=1,后续命中累加 `alert.deduplicated_count` | Phase 3.1 `b284fa3` |
| CEP 攻击链 | `rule-ssh-bruteforce-success-001`(失败→成功序列) | Phase 3.2 `da1a0f6` |
| 规则元数据 | 规则带 MITRE `attack.*` 标签,告警输出 `rule.tags` / `rule.status` / `alert.risk_score` | Phase 3.0-3.2 |
| 告警生命周期 | `triage-alert.py`:5 态 {open, acknowledged, investigating, resolved, closed} + `alert.status_updated_at` + `alert.analyst_verdict`(true_positive/false_positive/duplicate) | Phase 3.3 `6524fb6` |
| 基线异常 | `BaselineAnomalyFunction(baselineHours=24, minBaselineHours=3)`:μ+3σ,冷启动守卫 | Phase 3.5 `c6fb407` |
| 实体风险 | `infra/elasticsearch/entity-risk.py` + `asset-criticality.json` + `siem-entity-risk-template.json` | Phase 3.5 `c6fb407` |

## 3. 业界对标(参考模型)

### 3.1 能力域模型(逐条解读)

成熟 SIEM 的能力收敛为 **10 大能力域**(Splunk ES / QRadar / Sentinel / Elastic / SecOps 跨平台实测归纳)。这 10 个域构成 SIEM 的**完整生命周期流水线**——从数据进来到分析师处置:

```
① 采集 → ② 归一化 → ③ 检测/关联 → ④ 富化 → ⑤ 风险告警
    ↑                                                ↓
⑩ 部署弹性(底座,贯穿全程)                   ⑥ 合规报表
    ↑                                                ↓
⑨ SOAR(自动化处置) ← ⑧ 调查/案件 ← ⑦ UEBA/异常(兜底未知)
```

> 一句话定位:③ 是"**雷达**"(判断是不是攻击),⑤ 是"**调度台**"(决定报不报、怎么报)。③ 从源头减少误判(判得准→误报少),⑤ 从出口减少噪音(报得精→告警少),共同对付"告警太多"这个 SOC 头号痛点。

下面逐条说明每个域:**是什么 / 业界做法 / 我们的现状与计划**。

#### ① 采集与连接器(Ingestion)

- **是什么**:日志怎么进来——数据源种类、采集协议(推 / 拉)。
- **业界**:商业 SIEM 卖点之一是连接器数量(几百种:Windows Eventlog、Syslog、防火墙/VPN、云 trail 等);推(agent/beats 上报)vs 拉(连接器轮询/订阅)。
- **我们**:仅 tcp:5000 单源(SSH)。多源时拆 per-source pipeline(`pipelines.yml`),未来 tcp→kafka input 支持多实例。

#### ② 数据归一化(Normalization)

- **是什么**:把不同来源的字段翻译成统一字段模型——SIEM 的**灵魂**(不统一,规则/看板无法跨源复用)。
- **业界**:CIM(Splunk)/ECS(Elastic)/ASIM(Sentinel)/OCSF(开放),趋势是从厂商绑定模型向 OCSF 收敛(一次归一化、多工具可移植)。详见 3.3。
- **我们**:存储用 ECS(已做对);输出侧已附加 OCSF 视图(`Ocsf.java`,`ocsf.class_uid`/`ocsf.severity_id`,Phase 3.2 `da1a0f6`),完整 OCSF 字段映射仍待补。

#### ③ 检测与关联(Detection & Correlation)

- **是什么**:规则引擎,四种判断标准(特征匹配 / 窗口量与时序 / 统计偏离 / 攻击链序列)。详见 3.4。
- **业界**:确定性规则先行(业界共识:**关联 = 80% 领域知识 + 20% ML**);四层分别解决已知特征、高频行为、未知/内鬼、多阶段攻击。
- **我们**:四层均落地——① 规则检测(6 条)、② 窗口关联(1 条 tumbling,事件时间 5min≥5)、③ 基线异常(`BaselineAnomalyFunction`,24h μ+3σ)、④ CEP 攻击链(`rule-ssh-bruteforce-success-001`)。

#### ④ 威胁情报富化(Enrichment)

- **是什么**:给事件**附加上下文**(地理、IP 信誉、资产/用户上下文),是检测质量的放大器。
- **业界**:GeoIP/ASN(MaxMind)、IP 信誉(AbuseIPDB / VirusTotal / GreyNoise——GreyNoise 能识别大众扫描器,直接降背景噪声误报)、资产关键度/privilege/外暴露。
- **我们**:GeoIP 已落地(Logstash `geoip` filter,at-ingest,Phase 3.3 `6524fb6`,输出 `source.geo.*`);TI(IP 信誉)用轻量本地缓存查表起步,待排期。

#### ⑤ 风险告警(Risk-based Alerting)

- **是什么**:告警怎么产生、怎么排优先级——从"事件级告警"进化到"**实体级风险聚合**",取代静态 severity 一刀切。这是 2025-2026 的主流进化点。
- **业界**:
  - Splunk RBA:每条命中写风险事件 → 按实体聚合风险分 → 跨阈值才生成一条 finding;公式 `(基础分+加值)×乘数`(管理员 ×1.5、关键资产目标 ×1.5);宣称减 ~80% 有效告警量。
  - Elastic 实体风险评分:每小时重算、聚合近 30 天 open/ack 告警、按 host/user/service 分组,叠资产权重(Low 0.5 / Medium 1 / High 1.5 / Extreme 2);分档 `<20 / 20-40 / 40-70 / 70-90 / >90`。
  - SecOps 四机制抑制:节流(suppression_key + window)、排除(exclusions)、SOAR 限时抑制、聚类成案件。
  - verdict 回流闭环:结案打 true_positive/false_positive/duplicate → 按规则统计 FP 率 → FP>50% review(可减 50-80% 告警量不减覆盖)。
  - 痛点数据:62% SOC 告警被直接无视、55% 团队漏掉关键告警、84% 从业者倦怠。
- **我们**:已实现(Phase 3.1/3.3/3.5)——`AlertSuppressor` 按「规则 + 实体(source.ip/user.name)+ 处理时间 60min 对齐桶」抑制:首个命中即发 count=1 告警,窗口内后续命中累加 `alert.deduplicated_count` 不新建;`alert.risk_score` 数值评分 + 排序;实体风险聚合已由 `entity-risk.py`(entity-risk.py + asset-criticality.json)落地。已知边界:对齐桶跨窗口(11:59:59 与 12:00:01 归入不同桶);「首次命中时间起算的滑动抑制(TTL)」列为 P1 备选。

#### ⑥ 合规报表(Compliance Reporting)

- **是什么**:满足审计/合规要求的留存与报表能力。
- **业界**:PCI DSS v4.0(留存 ≥12 个月、近 3 月立即可查、防篡改)、SOC2(监控/权限日志)、GDPR(个人数据留存目的);商业 SIEM 有开箱合规包,**开源方案无开箱合规报表**(隐性工程成本高)。
- **我们**:ILM 长留存已落地(`siem-events-*` hot → delete 365d,满足 PCI 12 个月);合规报表 dashboard 仍远期。

#### ⑦ UEBA / 异常检测

- **是什么**:用基线/ML 发现"人/资产的异常行为"(impossible travel、深夜访问、行为突变),兜住规则覆盖不到的未知/零日/内鬼。
- **业界**:统计版(μ+3σ、z-score、peer group)+ ML 版(UEBA,需 30-90 天学习期);Elastic/Splunk 的 UEBA 是付费级能力。
- **我们**:完整版**明确不做**(无 ML 基础设施、学习期长);已对高频信号(认证失败率突增)实现 Flink 统计基线 job(`BaselineAnomalyFunction`,`rule-auth-rate-anomaly-001`,24h μ+3σ,冷启动守卫),限定误报可控场景。

#### ⑧ 调查 / 案件管理(Investigation & Case Management)

- **是什么**:分析师从"看到告警"到"搞清来龙去脉"到"结案"的工作台。
- **业界**:三线流转(open→acknowledged→investigating→resolved→closed)、事件时间线、相关事件聚合;结案强制打 **verdict**(true_positive/false_positive/duplicate)并**回流调优规则**——这是误报闭环的关键纪律。
- **我们**:已实现——`alert.status` 5 态 {open, acknowledged, investigating, resolved, closed} + `alert.status_updated_at` + `alert.analyst_verdict`(true_positive/false_positive/duplicate)(Phase 3.3 `6524fb6`);`related_events`(窗口关联事件)已产出;三线处置已由**控制台告警台**承接(story-04 `e3db7bc`,替代 triage-alert.py 交互版)。

#### ⑨ SOAR / Playbook 自动化

- **是什么**:检测到之后的**自动处置**(隔离主机、封禁 IP、发工单)。与 SIEM 是两个产品但生态绑定。
- **业界**:开源栈 TheHive(案件)+ Cortex(富化)+ MISP(威胁情报)+ Shuffle(编排);商业 Splunk SOAR、Sentinel automation。
- **我们**:**明确不做 SOAR 平台**;实体风险聚合已由 `entity-risk.py` 落地,控制台负责展示与资产关键度配置。是否迁移为 Java 定时任务按后续收益评估,不影响当前闭环。

#### ⑩ 部署弹性(Deployment Resilience)

- **是什么**:SIEM 本身怎么部署、怎么扩(云 / SaaS / 自托管、高可用、灾备)。
- **业界**:本地 → 混合 → 云原生三代演进;云原生 SIEM 的 MTTD 比本地低 ~63%,混合架构采集成本低 ~41%。
- **我们**:单机 WSL2 + Docker Desktop(最简形态)。Kafka 已扩 3 分区;保持单机但**预留扩展点**(Logstash 多实例、Flink 并行),不提前做 HA。

**优先级全景**:

| 已具备 ✅ | P0 立即 | P1 近期 | P2/远期 | 明确不做 ❌ |
| --- | --- | --- | --- | --- |
| ECS 归一化、规则检测(6 条)、窗口关联、CEP 攻击链、告警抑制/去重、风险评分、实体风险聚合、基线异常、告警生命周期(5 态)、GeoIP、Kibana 呈现 | (Phase 3.0-3.5 已全部落地,见 §2.1) | ⑧ 三线产品化(现 Kibana 过渡)、② OCSF 完整映射、⑩ 扩展点 | ⑥ 合规报表、④ TI 富化 | ⑦ UEBA/ML 完整版、⑨ SOAR、⑥ 报表包 |

> 应用顺序:**先补 ⑤(告警治理)和 ③ 的可靠性/关联,再谈覆盖广度**——Phase 3.0-3.5 已按此顺序落地(见 §2.1),后续别急着加规则。

### 3.2 三代演进与我们的定位

| 代际 | 特征 | 本项目的阶段 |
| --- | --- | --- |
| 1 代 | 被动日志聚合 + 签名关联 | ~Phase 1 |
| 2 代 | 规则/分析引擎驱动 + 合规报表 | **~Phase 2-3(当前)** |
| 3 代 | 云原生流式引擎 + UEBA/AI 评分 | 远期目标(明确后置) |

我们应扎实做好 2 代核心(检测/关联/告警管理/留存),3 代重能力(UEBA/ML、SOAR、网络流分析)明确砍掉。

### 3.3 归一化是 SIEM 灵魂

- 业界正从厂商绑定模型(CIM / ECS / ASIM / UDM)向**开放的 OCSF** 收敛:一次归一化 → 多工具可移植,规则/看板未来换平台不必重写。
- **本项目已做对一半**:存储 schema 用 ECS(点分、`source.ip`/`@timestamp`),是 Elastic 生态事实标准,方向正确。可移植层已起步(输出侧 OCSF 视图 `Ocsf.java`,Phase 3.2 `da1a0f6`),完整 OCSF 字段映射仍待补——保留未来对接 AWS Security Lake / 换平台的能力。

### 3.4 检测与关联四层(业界成熟分层)

| 层 | 手段 | 解决什么 | 本项目现状 |
| --- | --- | --- | --- |
| ① 规则检测 | 时间/阈值/模式规则 | 已知攻击特征 | ✅ 规则检测(6 条) |
| ② 窗口关联 | 滑动/滚动窗口,5-60min 关联窗口 | 事件时间因果、高频行为 | ✅ 窗口已实现(1 条 tumbling,事件时间 5min≥5) |
| ③ 基线/异常 | μ+3σ、z-score、peer group | 未知/零日/内鬼 | ✅ 基线已实现(baselineHours=24,μ+3σ,冷启动守卫) |
| ④ 攻击链关联 | 序列关联(CEP),弱信号一致性评分 | 多阶段攻击叙事 | ✅ CEP 已实现(rule-ssh-bruteforce-success-001) |

> 关键结论(业界):**关联是 80% 领域知识 + 20% ML,确定性规则必须先行**。

## 4. 差距分析(当前 → 目标)

| 能力 | 现状 | 目标 | 优先级 |
| --- | --- | --- | --- |
| 告警去重/抑制 | 无(1 事件 1 告警) | 规则级 suppression(按实体+时间窗口) | ✅ 已实现(b284fa3) |
| 风险评分 | 无(静态 severity 字符串) | 数值 risk_score + 实体级聚合(复刻 Splunk RBA) | ✅ 已实现(c6fb407) |
| 规则元数据 | 仅 id/name/type/severity/desc | + MITRE tags / risk_score / status / version / references | ◐ tags/risk_score/status 已实现(7e86478);rule.version 窗口/CEP/基线覆盖 + references 待补 |
| 告警幂等 | ES sink 无 `_id` | 确定性 `_id`(重放变 upsert) | ✅ 已实现(7e86478) |
| checkpoint 持久化 | 容器本地盘,重启即丢 | 挂载持久卷 + 显式 restart strategy | ✅ 已实现(7e86478) |
| 窗口 idle 处理 | 无(日志暂停窗口不关) | `.withIdleness(60s)` | ✅ 已实现(b284fa3) |
| 攻击链关联 | 无 | CEP 序列规则(失败→成功登录) | ✅ 已实现(da1a0f6) |
| OCSF 可移植层 | 无 | OCSF 字段映射 | ◐ 部分实现(da1a0f6):输出侧 OCSF 视图 |
| 告警生命周期 | 无 status/verdict | status + verdict + Kibana 三线 + 误报回流 | ✅ 已实现(6524fb6) |
| ILM 留存 | 无(按天裸索引) | delete 阶段 ILM(hot→delete 365d,无 warm) | ✅ 已实现(7e86478) |
| GeoIP 富化 | 无 | Logstash geoip filter(at-ingest) | ✅ 已实现(6524fb6) |
| 基线异常 | 无 | Flink 统计 job(滚动基线 24h) | ✅ 已实现(c6fb407) |
| 检测即代码 | 硬编码规则 | 规则 YAML + 测试夹具 + lint(CI 明确不做) | ✅ 已实现(f1739e0 规则 YAML 化 + 1671f51 console 启停;4c74f35 lint 门禁) |

## 5. 功能需求(按能力域,MoSCoW)

### 5.1 Must(Phase 3.0 — 可靠性基线)

- **F-R1 告警幂等**(✅ 已实现,`7e86478`):告警写入 ES 用确定性 `_id`(`DetectionJob.alertId()`),重启/重放变 upsert 不产生重复告警。
- **F-R2 持久化状态**(✅ 已实现,`7e86478`):Flink checkpoint/savepoint 落到持久卷;显式 EXACTLY_ONCE、restart-strategy、timeout。
- **F-R3 留存策略**(✅ 已实现,`7e86478`/`6524fb6`):`siem-events-*` 挂 ILM 生命周期(hot → delete `min_age 365d`,无 warm 阶段,单节点;满足 PCI 12 个月留存)。
- **F-R4 单节点健康**(✅ 已实现,`7e86478`):replica=0(消除 yellow 与写放大)、refresh_interval/压缩/translog 调优。
- **F-R5 规则元数据**(✅ 已实现,`7e86478`):Rule 增加 MITRE tags(`attack.*` 全小写)、risk_score、status;告警输出 `rule.tags`/`rule.status`/`alert.risk_score`/`rule.version`。YAML 声明化已落地(f1739e0);`rule.version` 四类产出器(单事件/窗口/CEP/基线)均已写入;references 已补(2026-08-16:6 条规则 YAML 均带 ATT&CK 参考链接,RuleLintTest 校验格式)。references 保留在规则元数据(供覆盖度/审计),不写入每条告警(避免存储膨胀)。

### 5.2 Must(Phase 3.1 — 减噪)

- **F-R6 告警抑制**(✅ 已实现,`b284fa3`,实现口径):`AlertSuppressor` keyBy(rule_id + 实体(source.ip/user.name)) + 处理时间对齐 60min 桶((now/windowMillis)×windowMillis) + registerProcessingTimeTimer + onTimer 产最终告警。首个命中即发 count=1 的告警,窗口内后续命中累加 `alert.deduplicated_count` 不新建。已知边界:对齐桶跨窗口(11:59:59 与 12:00:01 归入不同桶);「首次命中时间起算的滑动抑制(TTL)」列为 P1 备选。
- **F-R7 风险评分**(✅ 已实现,`c6fb407`):每条规则定数值 risk_score;Kibana 按 risk_score DESC 排序;实体风险聚合已由 `infra/elasticsearch/entity-risk.py`(entity-risk.py + asset-criticality.json)落地,控制台负责资产关键度配置与展示。

### 5.3 Should(Phase 3.2 — 检测工程化)

- **F-R8 攻击链关联**(✅ 已实现,`da1a0f6`):CEP 序列规则 `rule-ssh-bruteforce-success-001`(同源"多次认证失败 → 随后成功登录"= `attack.t1110.001`/`attack.t1078.002`)。
- **F-R9 OCSF 映射层**(◐ 部分实现,`da1a0f6`):输出侧已附加 OCSF 视图(`Ocsf.java`,`ocsf.class_uid`/`ocsf.severity_id`),完整字段映射见 `docs/design/ocsf-mapping.md`,待补全。
- **F-R10 检测即代码**(✅ 已实现,`f1739e0`/`1671f51`/`4c74f35`):规则 YAML(Sigma 风格)唯一来源 `infra/rules/*.yaml`(category=single_event/window/cep/baseline + 声明式 condition);Flink 启动按 `enabled` 注册;console 只读展示 + 启停(写 enabled),编辑走 Git/PR;动态编辑 P2 开放。正负夹具测试 + lint 门禁(`RuleLintTest`)已落地;**CI 明确不做**(2026-08-16 决策:个人项目无外部协作者,本地 mvn test 覆盖)。

### 5.4 Should(Phase 3.3 — 告警闭环与富化)

- **F-R11 告警生命周期**(✅ 已实现,`6524fb6`):`alert.status` 5 态 {open, acknowledged, investigating, resolved, closed} + `alert.status_updated_at` + `alert.analyst_verdict`(true_positive/false_positive/duplicate,下划线枚举,与 ES 数据一致),Kibana 三线视图(`triage-alert.py`),verdict 回流调优。
- **F-R12 GeoIP 富化**(✅ 已实现,`6524fb6`):Logstash geoip filter 加 `source.geo.*`(at-ingest,近零成本)。

### 5.5 Could(远期)

- **F-R13 基线异常**(✅ 已实现,`c6fb407`):Flink 滚动基线 job(`BaselineAnomalyFunction`,滚动 24h,可配 `baselineHours`,最小样本 `minBaselineHours=3`)μ+3σ 告警,冷启动守卫,仅高频信号。
- **F-R14 威胁情报**(✅ 已实现,`664f6a6`):TI 查表富化 MVP——Logstash `translate` filter 用本地字典(`infra/logstash/config/ti-malicious.yml`/`ti-confidence.yml`),写 `threat.is_malicious`/`threat.confidence`;字典更新脚本 `infra/ti/update-ti.py`。外部 feed(STIX/TAXII/AbuseIPDB)拉取为 P2+。
- **F-R15 资产关键度权重**(✅ 已实现,`c6fb407`):asset-criticality 表(`asset-criticality.json`)加权风险分,由 entity-risk.py 使用。
- **F-R16 相关事件反查/事件时间线**(✅ 已实现,2026-08-16):调查台案件详情实时关联 `siem-events` 生成时间线(`CaseService.timeline`,按实体+近 24h),`related_events` 快照提供告警关联跳转(见 08 §5.7 / story-07)。

## 6. 非功能需求

| 维度 | 需求 | 依据 |
| --- | --- | --- |
| 性能 | 单机支撑 ≥100 EPS 稳定摄入与实时检测(当前 SSH 单源远低于此,留足余量) | 单机 lab 定位 |
| 容量测算 | 按 EPS × 平均事件字节估算日增量(如 100 EPS × 1KB ≈ 8.6 GB/天),乘留存 365d + ILM 索引开销得总磁盘需求,建议磁盘预留 ≥ 测算值 1.5× | 见 04 文档 |
| 可用性 | Logstash 崩溃时 tcp 输入事件不丢(persistent queue 已落地,Phase 3.1 `b284fa3`);Kafka 侧可容忍在途窗口丢失(已双写 ES) | PQ + 双写边界 |
| 数据一致性 | 告警 at-least-once + 幂等 `_id`(不重复);事件 at-least-once | ES8 sink 语义 |
| 容灾(RPO/RTO) | checkpoint 60s 快照 → RPO ≤60s;RTO 建议以重启恢复时间衡量(持久卷恢复 + 重放),目标 ≤5min | 见 03 文档 |
| 可扩展 | Kafka 分区 1→3(已 3 分区)、Logstash 可拆多 pipeline、Flink 算子可扩并行;保留横向路径 | 预留,不提前做 |
| 安全 | 单机 PLAINTEXT 为显式决策并文档化;暴露网络前启用 basic auth + RBAC(最小权限) | 见 03 文档 |
| 合规 | PCI DSS v4.0 留存 ≥12 个月/近 3 月可查;留存策略书面化(ILM hot→delete 365d 已落地);合规报表包远期 | 见 04 文档 |
| 告警量 SLA | 单实体单规则 ≤1 条/h(AlertSuppressor 60min 抑制桶保证);全局告警量按月目标控制 | Phase 3.1 `b284fa3` |
| 可运维 | 监控:ES 健康/分片、Kafka lag、Flink checkpoint 时延、Logstash flow 指标 | 见 03 文档 |

## 7. 明确不做(范围边界)

- ❌ 完整 UEBA / ML 平台(无 ML 基础设施,基线异常仅对高频信号做统计版)
- ❌ 网络流分析(NetFlow/sFlow,需额外采集链)
- ❌ SOAR 编排引擎(TheHive/Shuffle 等;当前案件聚合由 Spring Boot `CaseService`/`CaseAggregateJob` 承载)
- ❌ 合规报表包(PCI/HIPAA/SOC2 模板)
- ❌ 独立威胁情报平台(以轻量查表/feed 起步)
- ❌ Schema Registry 引入(单生产者+单消费者+ECS+ES 模板已足够,出现第二生产者/消费者再评估)

## 8. 验收口径

- **可靠性**:重启 Flink job(从持久化 checkpoint 恢复)后,重复发同一批日志,`siem-alerts` 不出现重复告警(幂等 `_id` 验证)。✅ 已满足(Phase 3.0,`7e86478`)。
- **减噪**:同一实体同规则短时间重复命中,只产生 1 条告警(累加 `alert.deduplicated_count`)。✅ 已满足(Phase 3.1,`b284fa3`)。
- **留存**:`GET _cat/indices` 能看到 ILM 策略生效;超过保留期(365d,无 warm)的索引被自动删除。✅ 已满足(Phase 3.0,`7e86478`)。
- **可度量**:Kibana 能按 `alert.risk_score` 排序、按规则看告警量/误报率;ATT&CK 覆盖矩阵(按 `rule.tags`/`attack.*`)可见。✅ 已满足(Phase 3.0-3.2)。
- **检测能力**:攻击链 CEP 规则(`rule-ssh-bruteforce-success-001`)能对"失败→成功"序列产生单条告警。✅ 已满足(Phase 3.2,`da1a0f6`)。
