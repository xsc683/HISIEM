# Phase 3 设计 — 检测工程化

> 状态:设计稿 · 2026-08-11
> 聚焦规则引擎的演进方向、规则元数据标准化、告警生命周期闭环、富化与检测质量。组件无关的落地细节见 `03-component-best-practices.md`。

## 1. 规则引擎演进:三层抽象

业界检测分层 = **基础特征匹配 → 统计窗口 → 序列关联(CEP)**,本项目三层均已实现。抽象如下:

```
规则(Rule)
├── 单事件规则      条件立即匹配       → 现有 RuleRegistry / DetectionFunction ✅
├── 时间窗口规则    统计阈值(事件时间) → 现有 WindowRule / WindowRuleFunction ✅
└── 序列规则(CEP)  时序模式(攻击链)    → 已实现 flink-cep Pattern(Phase 3.2, rule-ssh-bruteforce-success-001) ✅
```

**序列规则示例**(已实现,示范攻击链叙事而非单点告警):
> 同 `source.ip` 在 10 分钟(事件时间)内"≥5 次认证失败 → 随后 1 次成功登录" → 单条 critical 告警(attack.t1110.001 暴力破解 + attack.t1078.002 有效账户)。

CEP 表达:`Pattern.begin("failures").where(fail).times(5, 100).next("success").where(success).within(10.minutes())`,keyBy(source.ip)。
> 注:代码用 `.times(5, 100)`(5-100 次连续失败,较单次阈值更严,覆盖批量爆破),非 `.times(5)`。

## 2. 规则元数据模型(P0,最低成本最大杠杆)

`Rule.java`(RuleRegistry)已实现 `id/name/type/severity(String)/description/condition/riskScore/tags/status/version`。业界标准(检测即代码/Sigma)还要求补:

| 字段 | 类型 | 示例(6 条现有规则映射,含窗口/CEP/基线) |
| --- | --- | --- |
| `tags` | String[] | MITRE 技术 ID:`attack.t1110.001`(认证失败/暴力破解)、`attack.t1078.002`(root/弱账号) |
| `riskScore` | int(0-100) | 认证失败 40、root 登录失败 81、弱账号 47、窗口暴力破解 73、暴力破解成功(CEP)90、基线突增 60(按危害定) |
| `status` | String | experimental / stable / deprecated |
| `version` | String | 规则版本 |
| `references` | String[] | 文档/告警参考链接(未落地,需设计 schema) |
| `falsepositives` | String[] | 已知误报场景(调优用)(未落地,需设计 schema) |

**告警模板同步加**:`alert.risk_score`(numeric)、`rule.tags`(keyword[])、`rule.status`(keyword)、`rule.version`(keyword)。severity 可保留字符串但 Kibana 排序改用 `alert.risk_score`。
> 注:`siem-alerts` 模板已含 `rule.version` mapping;四类产出器(单事件 DetectionFunction / 窗口 WindowRuleFunction / CEP BruteforceSuccessFunction / 基线 BaselineAnomalyFunction)均已写 `rule.version`(2026-08-16 补齐,WindowRuleTest 有回归断言)。

> ATT&CK 标注的价值 = **看得见覆盖盲区**。导出当前 6 规则覆盖矩阵(Detected/Logged/Blind),生成 ATT&CK Navigator layer JSON 放 `docs/design/`,按行业高价值技术排新规则优先级,避免只堆规则不测覆盖。

## 3. Sigma 结合(P2)

- Sigma 是厂商中立规则格式(SigmaHQ 3000+ 规则,pySigma 25+ 后端);`title/id/status/logsource/detection/condition/level/tags(attack.*)`。
- **限制**:Sigma 面向批量/调度查询,表达不了实时流窗口聚合与 CEP;转换后的规则必须针对本环境 tuning。
- **落地方式**:`infra/rules/*.yaml`(规则声明,含 `enabled` 字段,Sigma 风格 + 正负测试夹具)作为**规则唯一来源**;Flink 启动时读取并按 `enabled` 注册。构建期转换器(pySigma 自定义 backend 或手写 Java 生成器)产出 `RuleRegistry` 的 Java Condition AST。单事件规则可直接转;窗口/CEP/基线规则保留 Java 实现但共享元数据字段。启停 = 改 `enabled` → rebuild → redeploy → 重启 job(成本 = 一次重部署);「无需重启的动态启停」列 P1。
- **level→severity 映射建议**:Sigma `level`(`informational/low/medium/high/critical`)与 `alert.severity` 一一对应(`informational/low/medium/high/critical`),转换时无损保留,便于告警排序/Kibana 过滤沿用既有字段。

## 4. 告警生命周期闭环(P0/P1)

### 4.1 去重与抑制(P0,已实现 Phase 3.1-F6)

- **现状**:单事件规则 1 事件 1 告警(噪音源),`AlertSuppressor`(flink/.../AlertSuppressor.java)已落地解决。
- **实现口径**:keyBy(rule_id + 实体(source.ip 优先,其次 user.name)) + **处理时间对齐 60min 桶**(`(now/windowMillis)*windowMillis`)+ `registerProcessingTimeTimer` + `onTimer` 产最终告警。首个命中立即产出 count=1 的告警并登记窗口结束定时器;窗口内后续命中仅累加 `alert.deduplicated_count` 不新建;窗口结束 `onTimer` 用首个告警 JSON(首事件 @timestamp 不变 → _id 稳定,ES upsert 覆盖更新)产出最终 count,随后清状态。
- **验收(Given-When-Then)**:
  - Given 同 rule+IP 的 3 次命中落在同一 60min 抑制桶,When 依次流入,Then 仅产出 1 条告警且 `alert.deduplicated_count=3`;
  - Given 第 61min 再次命中,When 流入,Then 产出新告警(落入下一桶)。
- **已知权衡**:对齐桶边界(11:59:59 与 12:00:01 落不同桶)→ 边界两侧的同实体命中不会合并。此为刻意取舍(实现简单、state 生命周期确定);「首次命中时间起算的滑动抑制(TTL)」列 P1 备选。

### 4.2 风险评分(P0/P2)

- **规则级**:`alert.risk_score`(0-100,按危害定值)。
- **实体级**(复刻 Splunk RBA / Elastic entity risk scoring,P2):**`infra/elasticsearch/entity-risk.py` 已落地**(+ `asset-criticality.json` + `siem-entity-risk-template.json`),定时聚合近 30 天告警——按 `source.ip` / `user.name` 对 `alert.risk_score` 求和,叠资产关键度权重(Low 0.5/Medium 1/High 1.5/Extreme 2);分档 `<20 Unknown / 20-40 Low / 40-70 Moderate / 70-90 High / >90 Critical`,写 `siem-entity-risk` 索引(_id = type-value,幂等)。alert-service(Spring Boot 占位工程)化另行 story,当前 entity-risk.py 即产品雏形。
- **聚合取舍(sum vs max)**:当前实现用 **sum**(累计威胁量——告警越多分越高,体现"持续被攻击"程度);**max**(取单条最严重告警)更反映单点峰值风险。可取 sum+count 双列或 max 按需选。**改进点**:现查询只按 `alert.created_at` 范围过滤,**不过滤 `alert.status`**(closed 告警也计入),后续可加 status 过滤避免已结案告警推高实体分。
- **优先级 = 风险分 DESC + 资产关键性 + ATT&CK tactic 覆盖多 + 规则频度**(极少触发的规则信号更强)。

### 4.3 三线流转与误报闭环(P1,已实现 Phase 3.3)

告警状态机 = **5 态** `{open, acknowledged, investigating, resolved, closed}`,核心叙事 open→ack→closed,含 investigating/resolved 与回退(triage-alert.py 已实现):

| 当前状态 | 事件 | 下一状态 |
| --- | --- | --- |
| open | ack | acknowledged |
| open | 直接处置并选 verdict | resolved / closed |
| acknowledged | 开始调查 | investigating |
| acknowledged | 结案并选 verdict | closed |
| investigating | 处置完成(选 verdict) | resolved |
| investigating | 判定误报/重复 | closed |
| resolved | 复查发现遗漏 → 重开 | open(回退) |
| closed | 重开复查 | open(回退) |

字段:`alert.status` / `alert.analyst_verdict` / `alert.status_updated_at`。verdict 枚举 = **`true_positive` / `false_positive` / `duplicate`**(下划线,与 ES 数据一致)。

**FP 率查询**(误报闭环输入,按 rule_id 分组):
```
GET /siem-alerts/_search
{ "size": 0,
  "query": { "exists": { "field": "alert.analyst_verdict" } },
  "aggs": { "by_rule": { "terms": { "field": "alert.rule_id" },
      "aggs": { "fp": { "filter": { "term": { "alert.analyst_verdict": "false_positive" } } } } } } }
```
FP 率 = `fp.doc_count` ÷ `by_rule.doc_count`(**分母 = 该规则已打 verdict 的告警总数**;若改为 ÷ 该规则全部告警,则反映"该规则噪音占比")。FP>50% 触发 review(加反条件/调阈值/退役)。

> 关键纪律:verdict 必须回流到规则,否则每次 triage 都是孤立的 one-off。业界参考:噪声规则可消耗 2 名全职分析师/天。交互化落地见 `docs/story/story-04-alert-triage.md`(triage-alert.py 为 CLI 雏形)。

## 5. 富化(P2)

- **时机**:at-ingest(入库前)——上下文一致、规则直接用、历史永久保留;query-time 贵且源变更丢上下文。
- **GeoIP(优先)**:Logstash `geoip` filter 内建、近零成本,产出 `source.geo.country_name/city/location`。
- **威胁情报(✅ 已实现 MVP,`664f6a6`)**:轻量本地字典(`infra/logstash/config/ti-malicious.yml`/`ti-confidence.yml`),Logstash `translate` filter 查表,写 `threat.is_malicious`/`threat.confidence`(字符串 fallback);字典更新脚本 `infra/ti/update-ti.py`。外部 feed(STIX/TAXII/AbuseIPDB CSV 拉取)与 GreyNoise 仍为 P2+ 未做。详见 `docs/design/threat-intel.md`(方案互链)。
- **规则/告警字段一致**:富化在检测前完成,保证 `siem-alerts` 的 `source.geo.*` 等字段可用。

## 6. 检测质量(检测即代码,P2)

1. **规则进 Git**(✅ 已落地,`f1739e0`):`infra/rules/*.yaml` 唯一来源,PR 评审 = 质量门禁 + 审计轨迹;Flink 启动按 `enabled` 注册(检测即代码,story-03)。
2. **四层测试**:
   - 语法/lint(✅ 已落地,`4c74f35`):`RuleLintTest` 覆盖 UUID 唯一性与格式 `^[0-9a-f-]{36}$`;`status`/`level` 枚举;MITRE tag 格式 `^attack\.t\d+\.?\d*$`;字段名在 ECS mapping / 索引模板内。**CI 明确不做**(2026-08-16 决策:单人项目无外部协作者,本地 `mvn test` 即为门禁)。
   - 正负夹具(✅ 已落地):`EventConditionsTest`(CEP 正负)/ `BaselineAnomalyTest`(基线正常/异常)已覆盖;窗口夹具见 `WindowRuleTest`。
   - 历史回放(⏳ 待做):两种可执行方案——**Kafka 重放**(近 30 天 `siem-events` 重发到 topic 观察新规则命中);或 **ES 历史 dry-run 脚本**(`infra/kibana/replay-dryrun.py`,规划中)对 `siem-events-*` 查询统计命中率。
   - 攻击模拟(Atomic Red Team,远期)。
3. **渐进发布**(⏳ 待做):新规则默认 `status: experimental` 且只写 `siem-alerts-test` 索引观察,再提升级。**sink 分流方案**:Flink ES sink 的 elementConverter 按 `rule.status` 选 index(`experimental` → `siem-alerts-test`,`stable` → `siem-alerts`),无需两套 sink。
4. **复用现有测试**(✅ 部分):`RuleEngineTest`/`WindowRuleTest`/`EventConditionsTest`/`BaselineAnomalyTest` 已作为规则驱动回归(Flink 27 用例);单事件规则经 RuleBuilder 由 YAML condition 驱动。

## 7. 合规与留存(远期,P3)

| 框架 | 硬约束 | 对应落地 |
| --- | --- | --- |
| PCI DSS v4.0 | 日志留存 ≥12 个月、近 3 月立即可查、防篡改 | ILM hot → delete 365d(与实现一致,无 warm 阶段,满足 PCI 12 个月)+ snapshot + 完整性(WORM 存储或哈希清单定期校验) |
| SOC2 | 审计期望留存策略书面化且与实际一致 | 本文档 + ILM 配置即策略 |
| GDPR | IP/用户名是个人数据,留存需明确目的 | `event.raw` 说明留存目的;长期归档考虑用户字段哈希化 |

## 8. 检测规则规划清单(建议新增顺序)

| 优先级 | 规则 | 类型 | MITRE | 状态 |
| --- | --- | --- | --- | --- |
| P0 | 暴力破解→成功登录(攻击链示范) | CEP 序列 | attack.t1110.001 / attack.t1078.002 | ✅ 已实现 |
| P0 | root 登录失败/弱账号 → 高 risk_score | 单事件(元数据增强) | attack.t1078.002 | ✅ 已实现 |
| P1 | 非工作时间成功登录(时间上下文) | 单事件 + 时间条件 | attack.t1078 | 规划 |
| P1 | 同源多用户失败尝试(横向用户名枚举) | 窗口 | attack.t1110.003 | 规划 |
| P2 | 单主机认证失败率突增(基线异常,滚动 24h,可配 baselineHours) | 统计 job | attack.t1110.001 | ✅ 已实现 |

> 原则:先确定性规则(已确定高价值)、再统计基线(误报可控的场景)、UEBA/impossible-travel 在无 ML 基础设施前不做。
