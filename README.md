# HISIEM 平台 — 轻量级 SIEM

基于 **Elastic Stack + Flink** 的轻量级 SIEM(Security Information and Event Management),覆盖日志采集、解析、实时检测、告警存储与可视化。

**项目状态:Phase 1(管道 MVP)+ Phase 2(架构完善)全部完成并端到端验证。**

## 数据链路

```
Linux 日志 ──> Logstash (Grok 解析 + ECS 标准化)
                  │
                  ├──> Elasticsearch  siem-events-*  (原始事件,按天索引)
                  │
                  └──> Kafka  siem-events  (事件总线)
                                │
                                └──> Flink  DetectionJob  (规则引擎)
                                          │
                                          ├── 单事件规则 → 告警
                                          └── 时间窗口规则(暴力破解)→ 告警
                                          │
                                          └──> Elasticsearch  siem-alerts
                                                          │
                                                          └──> Kibana (SIEM 总览 dashboard)
```

**组件职责**:Logstash 只做解析/标准化,不做检测;Kafka 是事件总线,解耦生产与消费;Flink 是检测引擎;ES 负责事件/告警存储与检索;Kibana 负责可视化;PostgreSQL 负责控制面事务数据。

## 仓库结构

```
SIEM/
├── pom.xml / src/          Spring Boot 应用(占位,未来 alert-service 告警服务)
├── flink/                  独立 Flink job 工程(规则引擎 + 检测任务)
│   ├── pom.xml             Flink 2.1,shade 打 jar,mainClass com.siem.DetectionJob
│   └── src/{main,test}/    规则引擎代码 + JUnit 测试
├── infra/                  基础设施配置(唯一来源,deploy.sh 同步到部署环境)
│   ├── docker-compose.yml  PostgreSQL/ES/Kibana/Logstash/Kafka/Flink 编排
│   ├── logstash/           Grok 解析规则
│   ├── elasticsearch/      索引模板 + 应用脚本
│   ├── kibana/             dashboard 创建脚本 + NDJSON 导出
│   ├── simulator/          日志模拟器(含暴力破解测试脚本)
│   └── deploy.sh           同步仓库 → 部署环境 + 构建 + 拷贝 jar
├── docs/                   设计文档(架构/部署/决策/规则引擎)
└── CLAUDE.md               面向 AI 会话的项目速览
```

## 文档入口

| 文档 | 内容 |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | 系统架构、数据流、Schema、规则引擎概览 |
| [docs/deployment.md](docs/deployment.md) | **新机器部署指南**(换环境必备) |
| [docs/design-decisions.md](docs/design-decisions.md) | 设计决策 + 踩坑记录 |
| [docs/event-alert-schema.md](docs/event-alert-schema.md) | Event/Alert Schema 详细设计 |
| [docs/rule-engine.md](docs/rule-engine.md) | 规则引擎使用与扩展 |

## 快速开始(新机器)

```bash
# 1. 克隆仓库,进入 infra/
# 2. 部署基础设施(docker compose)
wsl bash /mnt/d/Project/SIEM/infra/deploy.sh   # 同步到 ~/projects/mini-siem 并构建
cd ~/projects/mini-siem && docker compose up -d
# 3. 应用 ES 索引模板
bash /mnt/d/Project/SIEM/infra/elasticsearch/apply-templates.sh
# 4. 创建 Kibana dashboard
bash /mnt/d/Project/SIEM/infra/kibana/create-dashboards.sh
# 5. 提交 Flink 检测 job
docker exec siem-flink-jobmanager flink run -d /opt/flink/detection-job-1.0.jar
# 6. 验证(发一条测试日志)
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000
```

> 详细步骤见 [docs/deployment.md](docs/deployment.md)。

## 已实现能力

- ✅ Logstash Grok 解析 + ECS 字段标准化(`@timestamp` 为真实日志时间)
- ✅ Kafka 事件总线(`siem-events`)
- ✅ Flink 规则引擎:
  - 单事件规则 3 条(SSH 认证失败 / root 认证失败 / 常见账号爆破)
  - 时间窗口规则 1 条(同源 IP 5 分钟 ≥5 次失败 → 暴力破解 critical 告警)
- ✅ 告警扁平 Schema(`siem-alerts`,含 `event.raw`、`event_count`、`related_events`)
- ✅ ES 索引模板(`siem-events-*`、`siem-alerts`)
- ✅ Kibana "SIEM 总览" dashboard
- ✅ Flink checkpointing(重启不重放历史)
- ✅ JUnit 测试(9 用例)
