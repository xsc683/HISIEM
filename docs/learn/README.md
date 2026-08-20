# docs/learn — 入门功课(基础概念与关键组件)

> 本系列为**基础性学习材料**,系统讲解 SIEM 平台设计概念、事件→告警→案件流程的称谓,以及关键组件(Kafka、Elasticsearch、Flink、Logstash)的核心原理。控制面实践还应结合 `src/`、PostgreSQL/Flyway 与 `docs/roadmap-next.md` 阅读。
> 每一份文档均以 **正式定义 + 场景举例 + 本项目对应** 的方式组织,并锚定本项目的实际代码与配置,可作为理解 `docs/design/` 设计文档的基础。

## 阅读建议(推荐顺序)

```text
第 1 步  01-siem-basics.md            SIEM 的定义、事件→告警术语链(建立整体心智模型)
第 2 步  02-pipeline-walkthrough.md   本项目数据面全流程走读(将术语与具体系统对应)
第 3 步  03-kafka.md                  数据如何流转(Kafka 概念)
第 4 步  04-elasticsearch.md          数据如何存储与检索(ES 概念)
第 5 步  05-flink.md                  数据如何被检测(Flink 概念)
第 6 步  06-logstash.md               数据如何被解析(Logstash 概念)
读完     → 回看 docs/design/01-requirements.md 的能力域模型,再用控制台 API 验证案件/权限/运维控制面
```

> 前置要求:能跑通本项目部署(见 `docs/deployment.md`),并发送过一条测试日志。当前部署与端到端验证已完成；学习者仍需自行完成每篇文档末尾的自测。

## 文档地图

| 文档 | 内容 | 读后能回答 |
| --- | --- | --- |
| [01-siem-basics.md](01-siem-basics.md) | SIEM 定义、日志/事件/命中/告警/案件术语链、TP/FP、severity/risk_score、时间语义 | 数据在 SIEM 中每个阶段的确切称谓与产生者 |
| [02-pipeline-walkthrough.md](02-pipeline-walkthrough.md) | 以一条真实日志走读数据面,再说明告警如何进入控制台案件 | 自己的日志和告警到底经过了哪些处理 |
| [03-kafka.md](03-kafka.md) | topic/分区/offset/消费组/副本/retention、minISR 等可靠性参数、Spring Kafka 并行消费(扩展) | Kafka 作为事件总线的定位与机制 |
| [04-elasticsearch.md](04-elasticsearch.md) | 索引/文档/mapping/字段类型/分片/检索聚合/ILM | ES 如何存储与检索事件与告警 |
| [05-flink.md](05-flink.md) | DataStream/算子/分组/窗口/事件时间/watermark/状态/checkpoint | DetectionJob 各算子的作用与容错机制 |
| [06-logstash.md](06-logstash.md) | input/filter/output pipeline、grok 原理、队列机制 | logstash.conf 各段的作用与解析原理 |

## 统一组织方式

每份文档都包含以下部分:
1. **定义**:概念的形式化定义与要点。
2. **为什么 SIEM 需要它**:该组件在 SIEM 管道中的角色与具体能力。
3. **核心概念**:每个概念给出定义、关键性质与场景举例。
4. **本项目对应**:概念在本项目代码/配置中的具体位置。
5. **常见问题与设计关注点**:已踩过的坑与设计稿关注事项。
6. **动手验证**:可在本环境执行的命令。
7. **自测**:检验是否掌握关键结论。

## 读完自查

- 能否画出本项目的完整数据流(日志 → 事件 → 命中 → 告警),并指出每个阶段的组件与文件?
- 能否解释:`@timestamp` 为何采用事件时间、watermark 的作用、确定性 `_id` 防什么、mapping 为何创建后不可修改?
- 能否看懂 `docs/design/03-component-best-practices.md` 中每条落地项改动哪个文件、为什么?

> 若某份文档仍有疑问,请指出具体段落,可针对性补充讲解。
