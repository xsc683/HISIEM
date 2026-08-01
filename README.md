# HISIEM 项目

轻量级 SIEM(Security Information and Event Management)。**Phase 1** 端到端管道 MVP 已完成并验证,当前进入 **Phase 2** 架构完善阶段。

## 数据链路

```
Linux 日志 ──> Logstash(Grok 解析/标准化) ──> Kafka(siem-events) ──> Flink(DetectionJob) ──> Elasticsearch(siem-alerts)
```

各组件职责:Logstash 只解析、不检测;Kafka 只做事件总线;Flink 是检测引擎;ES 负责存储+搜索。

## 仓库结构

```
hsiem-platform/
├── pom.xml                 Spring Boot 应用(未来 alert-service/web,当前仅 hello 测试)
├── src/                    Spring Boot 代码 (com.xscsiem.hsiem_platform)
├── flink/                  独立 Flink job 工程 (com.siem:detection-job:1.0)
│   └── src/main/java/com/siem/DetectionJob.java
├── infra/                  基础设施配置(唯一来源,deploy.sh 同步到 WSL)
│   ├── docker-compose.yml
│   ├── logstash/pipeline/logstash.conf
│   ├── elasticsearch/      ⏳ Phase 2: index mapping
│   ├── kafka/              ⏳ Phase 2: topic 规划
│   ├── kibana/             ⏳ Phase 2: dashboard
│   ├── simulator/          ⏳ Phase 2: 日志模拟器
│   └── deploy.sh           同步到 WSL 并构建部署
└── docs/                   ⏳ Phase 2 设计文档
```

## 部署环境

本机 Windows + WSL2 + Docker Desktop。组件:ES 8.14 / Kibana 8.14 / Logstash 8.14 / Kafka 3.8 / Flink 2.1。基础设施运行于 WSL `~/projects/mini-siem`,本仓库是**唯一来源**。

## 常用命令

```bash
# 构建 Spring Boot 应用
./mvnw clean package

# 构建 Flink job jar
mvn -f flink/pom.xml clean package

# 部署(同步到 WSL + 构建 + 拷贝 jar 进 jobmanager 容器)
wsl bash infra/deploy.sh

# 提交 Flink job(更新运行中的 job 前先 cancel 旧的)
docker exec siem-flink-jobmanager flink run -d /opt/flink/detection-job-1.0.jar

# 发送一条测试日志
echo 'Jul 31 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000
```

## 已确认的关键事实

- Logstash 输出:ES `siem-auth-%{+YYYY.MM.dd}`(原生事件,按天)+ Kafka `siem-events`。
- ES 索引:`siem-auth-*`(事件)、`siem-alerts`(告警)。
- 已知待改进:grok 未把 `timestamp` 转成 `@timestamp`(Phase 2 处理);检测规则为硬编码单条(Phase 2 抽象为规则引擎)。
