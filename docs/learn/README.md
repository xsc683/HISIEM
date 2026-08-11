# docs/learn — 入门功课

> 目的:补上 SIEM 平台设计概念 + 事件→告警流程称谓 + 关键组件(Kafka/ES/Flink/Logstash)的基础认知。
> 特点:**每个概念都锚定本项目的实际代码和配置**——读完能看懂 `docs/design/` 的设计文档和你已经跑通的管道。

## 怎么读(建议顺序)

```text
第 1 步  01-siem-basics.md            SIEM 是什么 + 事件→告警术语链(先有"心智模型")
第 2 步  02-pipeline-walkthrough.md   用本项目管道走一遍全流程(把术语和实际系统对上)
第 3 步  03-kafka.md                  四大组件:先懂数据怎么流转
第 4 步  04-elasticsearch.md          再懂数据怎么存和查
第 5 步  05-flink.md                  再懂检测引擎怎么算
第 6 步  06-logstash.md               再懂入口怎么解析
读完     → 回看 docs/design/01-requirements.md 的能力域模型,应该都能对上号
```

> 前置要求:能跑通本项目的部署(见 `docs/deployment.md`),发过一条测试日志。

## 文档地图

| 文档 | 内容 | 读后能回答 |
| --- | --- | --- |
| [01-siem-basics.md](01-siem-basics.md) | SIEM 定义、日志/事件/告警/案件术语链、TP/FP、severity/risk_score 等 | 数据在 SIEM 里的每个阶段叫什么 |
| [02-pipeline-walkthrough.md](02-pipeline-walkthrough.md) | 一条真实日志从发送到告警的完整旅程,每步对应文件 | 我的日志到底经过了什么 |
| [03-kafka.md](03-kafka.md) | topic/分区/offset/消费组/retention,SIEM 里当缓冲 | Kafka 为什么是"事件总线" |
| [04-elasticsearch.md](04-elasticsearch.md) | 索引/文档/mapping/分片/查询/ILM | ES 怎么存和查事件/告警 |
| [05-flink.md](05-flink.md) | DataStream/算子/窗口/watermark/状态/checkpoint | DetectionJob 到底在干嘛 |
| [06-logstash.md](06-logstash.md) | input/filter/output、grok、pipeline、队列 | logstash.conf 每段是什么意思 |

## 每份文档的结构

每份都统一包含:一句话是什么 → 为什么 SIEM 需要它 → 核心概念表 → **本项目对应在哪** → 常见坑 → 动手验证命令 → 自测题。

## 读完自查

- 能不能画出本项目的完整数据流(日志 → 事件 → 告警)并指出每步的组件?
- 能不能解释:`@timestamp` 为什么是事件时间、watermark 是什么、`_id` 幂等防什么、mapping 为什么建了不能改?
- 能不能看懂 `docs/design/03-component-best-practices.md` 里每条落地项改的是哪个文件、为什么?

> 如果某份还是看不懂,或想更深,可以直接问 Claude:报上你看不懂的具体段落,我针对性讲。
