# 设计决策与踩坑记录

记录本项目的关键设计决策(为什么这么做)和部署中踩过的坑(避免重蹈覆辙)。

## 决策清单

### 决策 A:事件字段采用 ECS 对齐(推荐已采纳)

**决策**:事件字段用 Elastic Common Schema 命名(`source.ip`/`user.name`/`event.action`),不用自造字段(`src_ip`/`user`/`event_type`)。

**理由**:
- 跨源关联是 SIEM 灵魂,统一字段才能把不同来源日志关联(同一个 IP 在 SSH 失败日志和防火墙日志里)
- Elastic Security、Kibana 安全可视化、ML 均按 ECS 字段工作,对齐即兼容
- Sigma 社区规则(SigmaHQ)的 Elastic 后端按 ECS 映射,可直接套用
- 行业对标:Splunk CIM、Microsoft Sentinel ASIM 都是同样的"统一字段模型"

### 决策 B:@timestamp 语义

**决策**:`@timestamp` = 事件发生时间(不是摄入时间)。
- 事件:Logstash `date` filter 解析日志时间(时区 Asia/Shanghai)
- 告警:单事件规则 = 事件时间;窗口规则 = 窗口结束时间
- 检测时间单独存 `alert.created_at`

### 决策 C:索引命名

- 事件:`siem-events-%{+YYYY.MM.dd}`(按日志日期分索引)+ 别名 `siem-events`
- 告警:`siem-alerts`(单索引)

### 决策 D:扁平字段存储(实测修正)

**结论**:无论 ES `_source` 里是扁平 key 还是嵌套对象,**查询都用同一点分路径**(`source.ip`),行为一致。
- Logstash mutate/grok 建的点分字段在 ES 里是**扁平 key**;只有 ECS 自动加的 `event.original` 是嵌套
- **Logstash 8.14 没有 `naming_strategy` 选项**(配置会报 Unknown setting),无法强制输出扁平/嵌套
- ES 索引模板用嵌套对象 mapping(`source.properties.ip`),扁平 key 按点分路径正确索引

### 决策 E:规则引擎用 Java 对象(声明式)

**决策**:规则定义为 Java 对象(`Rule` + `Condition`),集中注册在 `RuleRegistry`。加规则 = 加一行。

**理由**:类型安全、IDE 补全、编译期检查、可 JUnit 单测。先代码内声明式,以后要外置 JSON 再迁移。

### 决策 F:时间窗口关联用事件时间

**决策**:窗口规则用 `TumblingEventTimeWindows` + 有界乱序 watermark(10s)。

**注意**:事件时间窗口在 **watermark 越过窗口边界时才关闭**。测试/模拟时,除了窗口内的事件,还要发一条时间戳在窗口之后的事件推进 watermark(见 `infra/simulator/brute-force-test.sh`)。

### 决策 G:checkpointing + committedOffsets

**决策**:`env.enableCheckpointing(60s)` + `setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))`。

**关键**:必须用 `committedOffsets(EARLIEST)`,**不能用 `earliest()`**。`earliest()` 忽略已提交的 group offset,重启必重放历史 → 重复告警。`committedOffsets` 从已提交 offset 恢复,首次运行才回退 earliest。

## 踩坑记录

### 坑 1:deploy.sh 对 bind mount 目录 rm -rf → Logstash exit 127

**症状**:Logstash 容器重启后 `Exited (127)`,`docker start` 报 OCI mount 错误 `docker-desktop-bind-mounts/... no such file or directory`。

**根因**:`~/projects/mini-siem/logstash` 是容器 bind mount 源。deploy.sh 里 `rm -rf` 删除重建目录后,Docker Desktop 的挂载注册失效。

**处理**:改 rsync 原地同步(不删目录);若已发生,重启 Docker Desktop 清缓存。

### 坑 2:Logstash `naming_strategy` 选项不存在

**症状**:Logstash 配置校验失败 `Unknown setting 'naming_strategy' for elasticsearch`。

**处理**:移除该配置(仓库已无)。

### 坑 3:Flattr vs nested 查询

见决策 D。结论:查询用点分路径(`source.ip`),与存储结构无关,不用纠结。

### 坑 4:Kibana dashboard 报 `Cannot read properties of undefined (reading 'searchSourceJSON')`

**根因**:Kibana dashboard saved object **本身必须有 `kibanaSavedObjectMeta.searchSourceJSON`**(存整页 query/filter)。手工创建时漏了会报错,单个可视化正常但 dashboard 崩。

**处理**:`infra/kibana/create_dashboards.py` 的 `build_dashboard_object` 已包含该字段。重建 dashboard 即可。

### 坑 5:重启 Flink job 后告警翻倍(重放)

见决策 G。`earliest()` 忽略已提交 offset,重启从最早读 → 重放全部历史事件 → 重复告警。用 `committedOffsets(EARLIEST)` 解决。

### 坑 6:checkpoint 默认在 cancel 时删除,savepoint 目录需 flink 属主

**根因**:Flink checkpointing 默认 `cleanup-mode=DELETE_ON_CANCELLATION`,`flink cancel` 后 checkpoint 被清空,无法从 checkpoint 恢复。cancel→restore 演练必须走 **savepoint**。

**处理**(已演练通过,2026-08-16):
1. `docker exec siem-flink-jobmanager sh -c "chown flink:flink /opt/flink/savepoints"`(docker exec 以 root 创建目录会导致 Flink 进程写失败,报 `Failed to create savepoint directory`)。
2. `flink cancel -s file:///opt/flink/savepoints <jobid>`(Flink 2.x 推荐 `flink stop -p <dir> <jobid>`,cancel -s 已弃用)。
3. `flink run -d -s file:///opt/flink/savepoints/<savepoint> /opt/flink/detection-job-1.0.jar` 恢复。
4. 验证:job RUNNING,发新日志检测正常,无重放重复(幂等 `_id` + committedOffsets)。

### 坑 7:ES snapshot fs 仓库需 `path.repo`,且 config 也要目录级挂载

**根因**:fs 仓库(`siem-backups`)要求 `path.repo` 在 elasticsearch.yml 中配置,未配置时注册报 `path.repo because this setting is empty`。单文件挂载 elasticsearch.yml 会踩 Docker Desktop 文件级 bind mount 的坑(坑 5)。

**处理**(2026-08-16):`infra/elasticsearch/config/` 目录级挂载 `/usr/share/elasticsearch/config`(补齐 jvm.options/log4j2 等默认文件,与 logstash 同款),elasticsearch.yml 带 `path.repo: ["/usr/share/elasticsearch/backups"]`。演练通过:注册仓库 → 快照 `siem-events-*` → 恢复到 `restored_*` → 计数一致(47=47)→ 清理临时索引。

## 资源参考

- ECS 官方文档:https://www.elastic.co/guide/en/ecs/current/index.html
- Elastic Security:https://www.elastic.co/security
- Sigma 规则集:https://github.com/SigmaHQ/sigma
- Flink KafkaSource:https://nightlies.apache.org/flink/flink-docs-stable/docs/connectors/datastream/kafka/
