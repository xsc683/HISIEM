# Phase 3 设计 — 检测工程化

> 状态:设计稿 · 2026-08-11
> 聚焦规则引擎的演进方向、规则元数据标准化、告警生命周期闭环、富化与检测质量。组件无关的落地细节见 `03-component-best-practices.md`。

## 1. 规则引擎演进:三层抽象

业界检测分层 = **基础特征匹配 → 统计窗口 → 序列关联(CEP)**,本项目规则引擎当前只实现前两层。目标抽象:

```
规则(Rule)
├── 单事件规则      条件立即匹配       → 现有 RuleRegistry / DetectionFunction ✅
├── 时间窗口规则    统计阈值(事件时间) → 现有 WindowRule / WindowRuleFunction ✅
└── 序列规则(CEP)  时序模式(攻击链)    → 新增 flink-cep Pattern  ❌ P1
```

**新增序列规则示例**(示范攻击链叙事而非单点告警):
> 同 `source.ip` 在 10 分钟(事件时间)内"≥5 次认证失败 → 随后 1 次成功登录" → 单条 critical 告警(T1110 暴力破解 + T1078 有效账户)。

CEP 表达:`Pattern.begin("fail").where(fail).times(5).next("success").where(success).within(10.minutes())`,keyBy(source.ip)。

## 2. 规则元数据模型(P0,最低成本最大杠杆)

当前 `Rule.java` 只有 `id/name/type/severity(String)/description/condition`。业界标准(检测即代码/Sigma)要求补:

| 字段 | 类型 | 示例(4 条现有规则映射) |
| --- | --- | --- |
| `tags` | String[] | MITRE 技术 ID:`attack.t1110.001`(认证失败/暴力破解)、`attack.t1078.002`(root/弱账号) |
| `riskScore` | int(0-100) | 暴力破解 73、root 登录失败 81、弱账号 47、认证失败 40(按危害定) |
| `status` | String | experimental / stable / deprecated |
| `version` | String | 规则版本 |
| `references` | String[] | 文档/告警参考链接 |
| `falsepositives` | String[] | 已知误报场景(调优用) |

**告警模板同步加**:`alert.risk_score`(numeric)、`rule.tags`(keyword[])、`rule.status`(keyword)。severity 可保留字符串但 Kibana 排序改用 `alert.risk_score`。

> ATT&CK 标注的价值 = **看得见覆盖盲区**。导出当前 4 规则覆盖矩阵(Detected/Logged/Blind),生成 ATT&CK Navigator layer JSON 放 `docs/design/`,按行业高价值技术排新规则优先级,避免只堆规则不测覆盖。

## 3. Sigma 结合(P2)

- Sigma 是厂商中立规则格式(SigmaHQ 3000+ 规则,pySigma 25+ 后端);`title/id/status/logsource/detection/condition/level/tags(attack.*)`。
- **限制**:Sigma 面向批量/调度查询,表达不了实时流窗口聚合与 CEP;转换后的规则必须针对本环境 tuning。
- **落地方式**:`infra/detections/*.yaml`(Sigma 风格,含正负测试夹具)作为**规则唯一来源**,构建期转换器(pySigma 自定义 backend 或手写 Java 生成器)产出 `RuleRegistry` 的 Java Condition AST。单事件规则可直接转;窗口/CEP 规则保留 Java 实现但共享元数据字段。

## 4. 告警生命周期闭环(P0/P1)

### 4.1 去重与抑制(P0)

- **现状**:单事件规则 1 事件 1 告警(噪音源)。
- **目标**:suppression = keyBy(rule_id + source.ip + user.name) + `ValueState<Boolean>` + `StateTtlConfig`(15-60min, OnCreateAndWrite, NeverReturnExpired);首个命中建告警,窗口内后续命中只累加 `alert.deduplicated_count` 不新建。
- **注意**:不要用对齐 epoch 的窗口去重(11:59:59 与 12:00:01 落不同窗口);TTL 清理是懒的,长期不访问的过期 state 会让 checkpoint 膨胀,用 `onTimer` 显式清 state 或用 `MapState`。

### 4.2 风险评分(P0/P2)

- **规则级**:`alert.risk_score`(0-100,按危害定值)。
- **实体级**(复刻 Splunk RBA / Elastic entity risk scoring,P2):alert-service(Spring Boot 占位工程)定时聚合——按 `host.name`/`user.name` 聚合近 30 天 open 告警加权和,叠资产关键度权重(Low 0.5/Medium 1/High 1.5/Extreme 2);分档 `<20 Unknown / 20-40 Low / 40-70 Moderate / 70-90 High / >90 Critical`。
- **优先级 = 风险分 DESC + 资产关键性 + ATT&CK tactic 覆盖多 + 规则频度**(极少触发的规则信号更强)。

### 4.3 三线流转与误报闭环(P1)

```
open(按 risk_score 排序)→ acknowledge → investigating → resolved/closed
每个结案必须选 verdict:true_positive / false_positive / duplicate
verdict 回流 → 按规则统计 FP 率 → FP>50% 触发 review(加反条件/调阈值/退役)
```

> 关键纪律:verdict 必须回流到规则,否则每次 triage 都是孤立的 one-off。业界参考:噪声规则可消耗 2 名全职分析师/天。

## 5. 富化(P2)

- **时机**:at-ingest(入库前)——上下文一致、规则直接用、历史永久保留;query-time 贵且源变更丢上下文。
- **GeoIP(优先)**:Logstash `geoip` filter 内建、近零成本,产出 `source.geo.country_name/city/location`。
- **威胁情报(后续)**:轻量本地缓存(AbuseIPDB 类 CSV 每日更新),Logstash `translate` filter 或 Flink AsyncFunction 查表,写 `event.is_malicious`/`confidence`;GreyNoise 可降互联网背景噪声误报。
- **规则/告警字段一致**:富化在检测前完成,保证 `siem-alerts` 的 `source.geo.*` 等字段可用。

## 6. 检测质量(检测即代码,P2)

1. **规则进 Git**:`infra/detections/*.yaml` 唯一来源,PR 评审 = 质量门禁 + 审计轨迹。
2. **四层测试**:
   - 语法/lint(校验 UUID 唯一、status/level 枚举、MITRE tag 格式、字段名存在于 ECS mapping)
   - 正负夹具(positive 命中攻击样本 / negative 不误报良性流量)
   - 历史回放(新规则 dry-run 近 30 天数据统计命中率)
   - 攻击模拟(Atomic Red Team,远期)
3. **渐进发布**:新规则默认 `status: experimental` 且只写 `siem-alerts-test` 索引观察,再提升级。
4. **复用现有测试**:扩展 `RuleEngineTest`/`WindowRuleTest`(当前 9 用例)为规则 YAML 驱动的回归测试。

## 7. 合规与留存(远期,P3)

| 框架 | 硬约束 | 对应落地 |
| --- | --- | --- |
| PCI DSS v4.0 | 日志留存 ≥12 个月、近 3 月立即可查、防篡改 | ILM hot 30d/warm 90d/delete 365d + snapshot + 完整性 |
| SOC2 | 审计期望留存策略书面化且与实际一致 | 本文档 + ILM 配置即策略 |
| GDPR | IP/用户名是个人数据,留存需明确目的 | `event.raw` 说明留存目的;长期归档考虑用户字段哈希化 |

## 8. 检测规则规划清单(建议新增顺序)

| 优先级 | 规则 | 类型 | MITRE |
| --- | --- | --- | --- |
| P0 | 暴力破解→成功登录(攻击链示范) | CEP 序列 | T1110 / T1078 |
| P0 | root 登录失败/弱账号 → 高 risk_score | 单事件(元数据增强) | T1078.002 |
| P1 | 非工作时间成功登录(时间上下文) | 单事件 + 时间条件 | T1078 |
| P1 | 同源多用户失败尝试(横向用户名枚举) | 窗口 | T1110.003 |
| P2 | 单主机认证失败率突增(基线异常,滚动 30 天) | 统计 job | T1110 |

> 原则:先确定性规则(已确定高价值)、再统计基线(误报可控的场景)、UEBA/impossible-travel 在无 ML 基础设施前不做。
