# HISIEM 平台 — 项目速览

轻量级 SIEM(Elastic Stack + Flink)。Phase 1(管道 MVP)+ Phase 2(架构完善:ECS schema、规则引擎、多规则、时间窗口关联、Kibana)全部完成。详细设计见 [docs/](docs/README.md)。

## 数据链路

```
日志 → Logstash(Grok+ECS) → Kafka(siem-events) → Flink(规则引擎) → ES(siem-alerts) + Kibana
                          ↘ ES(siem-events-*, 事件按天索引)
```

## 仓库布局

- `flink/` — **Flink 检测 job**(独立 Maven 工程,主类 `com.siem.DetectionJob`)。规则引擎:单事件(`RuleRegistry`/`DetectionFunction`)+ 窗口(`WindowRule`/`WindowRuleFunction`)
- `infra/` — 基础设施配置唯一来源:docker-compose、logstash、ES 模板、Kibana 脚本、simulator、deploy.sh
- `src/` `pom.xml` — Spring Boot 应用(占位,未来 alert-service)
- `docs/` — 架构/部署/决策/规则引擎文档

## 常用命令

```bash
# 构建 + 测试 Flink job(Windows 侧)
./mvnw -f flink/pom.xml clean package

# 部署(同步仓库 → WSL + 构建 + 拷 jar 进 jobmanager)
MSYS_NO_PATHCONV=1 wsl bash /mnt/d/Project/SIEM/infra/deploy.sh

# 提交/取消 Flink job(容器内)
docker exec siem-flink-jobmanager flink run -d /opt/flink/detection-job-1.0.jar
docker exec siem-flink-jobmanager flink cancel <JobID>   # 先 flink list 查 ID

# 应用 ES 模板 / 建 Kibana dashboard(在 WSL 内)
bash /mnt/d/Project/SIEM/infra/elasticsearch/apply-templates.sh
bash /mnt/d/Project/SIEM/infra/kibana/create-dashboards.sh

# 发测试日志 / 暴力破解测试
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000
bash /mnt/d/Project/SIEM/infra/simulator/brute-force-test.sh
```

> WSL 从 Windows 调用时加 `MSYS_NO_PATHCONV=1`(避免 Git Bash 路径转换)。

## 关键知识点(易踩坑)

1. **Flink offset**:`DetectionJob` 用 `committedOffsets(EARLIEST)`(不是 `earliest()`),否则重启重放历史 → 重复告警。已开 checkpointing(60s)。
2. **事件时间窗口**:窗口在 watermark 越过边界才关闭;模拟测试要发一条时间戳在窗口之后的事件推进 watermark。
3. **Logstash**:点分字段(`source.ip`)在 ES 里是扁平 key 但按嵌套 mapping 索引,查询用点分路径即可;`naming_strategy` 选项在 8.14 不存在。
4. **deploy.sh 不能 rm -rf bind mount 目录**(logstash),会破坏 Docker Desktop 挂载导致 exit 127,用 rsync 原地同步。
5. **Kibana dashboard** 对象必须带 `kibanaSavedObjectMeta.searchSourceJSON`。

## 告警/事件快速查询

```bash
curl -s "http://localhost:9200/siem-events-*/_count"
curl -s "http://localhost:9200/siem-alerts/_count"
```

## 测试

```bash
./mvnw -f flink/pom.xml test   # 9 个用例(RuleEngineTest + WindowRuleTest)
```
