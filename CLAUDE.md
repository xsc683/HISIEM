# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# HISIEM 平台 — 项目速览

轻量级 SIEM(Elastic Stack + Flink)。Phase 3.0-3.5 检测引擎基线与 Phase 4.0-4.3 控制台/运维能力已完成；控制面为 Spring Boot + PostgreSQL/Flyway，数据面为 Elastic Stack + Kafka + Flink。详细设计见 [docs/](docs/README.md)。

## 数据链路

```
日志 → Logstash(Grok+ECS) → Kafka(siem-events) → Flink(规则引擎) → ES(siem-alerts) + Kibana
                          ↘ ES(siem-events-*, 事件按天索引)
```

## 仓库布局

- `flink/` — **Flink 检测 job**(独立 Maven 工程,主类 `com.siem.DetectionJob`)。规则引擎:单事件(`RuleRegistry`/`DetectionFunction`)+ 窗口(`WindowRule`/`WindowRuleFunction`)
- `infra/` — 基础设施配置唯一来源:docker-compose、logstash、ES 模板、Kibana 脚本、simulator、deploy.sh
- `src/` `pom.xml` — Spring Boot 控制面 API(认证、接入、告警、案件、通知、运维)
- `web/` — React/Vite 控制台(路由表见 `web/src/routes.js`)
- `docs/` — 架构/部署/决策/规则引擎文档

## 常用命令

```bash
# 构建 + 测试 Flink job(Windows 侧)
# 根目录 ./mvnw 用于 Spring Boot 控制面；Flink job 必须用 -f flink/pom.xml。
./mvnw -f flink/pom.xml clean package                       # 构建(含测试)
./mvnw -f flink/pom.xml test                                # 全部用例
./mvnw -f flink/pom.xml test -Dtest=RuleEngineTest          # 单个测试类
./mvnw spring-boot:run                                     # 启动控制面(默认 8080)
npm --prefix web run dev                                   # 启动前端(默认 5173)

# 部署(同步仓库 → WSL + 构建 + 拷 jar 进 jobmanager)
MSYS_NO_PATHCONV=1 wsl bash /mnt/d/Project/SIEM/infra/deploy.sh

# 提交/取消 Flink job(容器内)
docker exec siem-flink-jobmanager flink run -d /opt/flink/detection-job-1.0.jar
docker exec siem-flink-jobmanager flink cancel <JobID>   # 先 flink list 查 ID

# 应用 ES 模板 / 建 Kibana dashboard(在 WSL 内)
bash /mnt/d/Project/SIEM/infra/elasticsearch/apply-templates.sh
bash /mnt/d/Project/SIEM/infra/kibana/create-dashboards.sh

# 建 Kafka topic(提交 Flink job 前必须执行;apache/kafka:3.8 默认关闭自动建主题)
bash /mnt/d/Project/SIEM/infra/kafka/create-topics.sh

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
5. **Docker Desktop 的"文件级 bind mount"会被 rsync 替换破坏**:compose 若单文件挂载(`./a.yml:/path/a.yml`),rsync 原地替换该文件(Docker Desktop 快照旧 inode)后,容器 restart/up 报 `mount ... no such file or directory`(exit 127)。**解法:改成目录级挂载**(`./logstash/config:/usr/share/logstash/config`,已在 docker-compose.yml;config 目录需内含 jvm.options/log4j2 等镜像默认文件,已从镜像拷入)。目录内文件替换不受影响。
6. **Logstash `--config.test_and_exit -f <conf>` 会引导新实例并争抢持久化队列锁**(`data/queue/main/.lock`,运行中实例持有)→ 误报校验失败。**需加 `--path.data=/tmp/<唯一>` 重定向**(ProcessLogstashDeployer 已处理)。
7. **Logstash 数组不接受尾逗号**:grok/date 的 match 数组末尾留 `,` 会让 `--config.test_and_exit` 报 `Expected one of ...` FATAL。生成器已避免(LogstashConfigGenerator)。
8. **Flink checkpoint 默认在 cancel 时删除**(cleanup-mode=DELETE_ON_CANCELLATION),cancel 后无法从 checkpoint 恢复。**cancel→restore 演练用 savepoint**:`flink cancel -s file:///opt/flink/savepoints <jobid>`(Flink 2.x 推荐 `flink stop -p <dir> <jobid>`);savepoint 目录需 `chown flink:flink`(docker exec 以 root 创建会使 Flink 进程写失败,报 `Failed to create savepoint directory`)。已演练通过(2026-08-16)。
9. **Kibana dashboard** 对象必须带 `kibanaSavedObjectMeta.searchSourceJSON`。
10. **Kafka topic 必须手动建**:`apache/kafka:3.8` 默认 `auto.create.topics.enable=false`,Flink KafkaSource 的元数据订阅不会触发建主题(只有生产者写入才建)。提交 Flink job 前先跑 `infra/kafka/create-topics.sh`(deploy.sh 只同步脚本,不执行)。
11. **Flink checkpoint 偶发卡滞 → 告警批量吐出(2026-08-16 观察到)**:现象 = Kafka consumer group `siem-detection` LAG 持续不降,告警在恢复后突然批量新增;日志报 `Checkpoint expired before completing`(checkpoint 5min 超时到期)。**根因**:瞬时负载(如 Logstash 重启时 ES 写入压力、大批日志涌入)使 ES sink 的 `maxInFlightRequests(5)/maxBufferedRequests(1000)` 缓冲积压,checkpoint barrier 被阻塞超时。**处置**:`flink cancel <jobid>` + 重新 `flink run -d`,重启后 checkpoint 恢复正常(60s 间隔 / 9-16ms 完成)。**待调优**:ES sink 缓冲参数、checkpoint timeout 或间隔需在真实负载下评估(记录为已知问题,05-roadmap 待办)。

## 告警/事件快速查询

```bash
curl -s "http://localhost:9200/siem-events-*/_count"
curl -s "http://localhost:9200/siem-alerts/_count"
```

## 测试

```bash
./mvnw test                                                # 根项目全部 65 个测试
./mvnw -f flink/pom.xml test                              # Flink 模块全部 30 个测试
./mvnw -f flink/pom.xml test -Dtest=RuleEngineTest        # 单个测试类
./mvnw -f flink/pom.xml test "-Dtest=WindowRuleTest#bruteForceAlertHasCountAndRelatedEvents"  # 单个方法
```
