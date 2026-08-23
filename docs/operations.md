# 运行与排障手册

> 定位：面向日常启动、健康确认和故障定位；新环境的完整安装/重建步骤仍以[部署指南](deployment.md)为准。

## 日常启动与快速确认

```bash
cd /mnt/d/Project/SIEM
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps
```

预期：PostgreSQL、Elasticsearch、Kafka、Logstash、Flink JobManager/TaskManager、Kibana 容器处于 `running`，有健康检查的容器显示 `healthy`。首次启动或修改镜像后，继续执行[部署指南](deployment.md)中的模板、规则和 Flink 作业步骤。

控制面启动后，可用管理员令牌检查运行态：

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/ops/health-scan
```

健康扫描关注“状态 + 语义 + 延迟”，不要只看进程是否存在。`UP` 表示探针通过；`DEGRADED` 表示仅完成有限检查；`DOWN` 才表示检查失败。

## 组件级检查

### Kafka

| 检查 | 命令/入口 | 通过标准 |
| --- | --- | --- |
| 容器 | `docker compose -f infra/docker-compose.yml ps kafka` | 容器运行且日志无持续重启 |
| 宿主机客户端 | `localhost:9092` | 能获取 `siem-events` 和两个 lifecycle topic 元数据 |
| 容器内客户端 | `kafka:9092` | 能列出 topic、读取 offset |
| 检测作业 | Flink 作业页面或 API | 作业 `RUNNING`，消费组 lag 可解释 |

`TimeoutException: Timed out waiting for a node assignment` 通常表示探针使用了错误的 listener、broker 尚未选主或 topic 元数据未就绪。先确认 `localhost:9092`（宿主机）与 `kafka:9092`（容器网络）没有混用，再检查：

```bash
docker logs --tail 100 siem-kafka
docker exec siem-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --describe --topic siem-events
```

SOAR 还需确认两个 topic 与消费组：

```bash
docker exec siem-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic siem-alert-lifecycle
docker exec siem-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic siem-case-lifecycle
docker exec siem-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group siem-soar-runtime
```

### Logstash

当前镜像的监控 API 绑定容器回环地址，宿主机 `localhost:9600` 不一定可达。因此健康扫描出现 `UP / degraded TCP / 监控 API 不可用，仅确认端口监听` 是有意的降级语义，不等同于 pipeline 正常。

进入容器确认 pipeline 和事件吞吐：

```bash
docker exec siem-logstash curl -fsS http://127.0.0.1:9600/_node/pipelines
docker logs --tail 100 siem-logstash
```

### Flink

确认 JobManager/TaskManager 正常后，查看检测作业状态；作业必须是 `RUNNING`。作业丢失或反复重启时，先检查 Kafka topic 分区数、checkpoint 和 ES sink 错误，再按[部署指南](deployment.md)重新提交，不要直接删除历史索引。

### Elasticsearch

```bash
curl -fsS http://localhost:9200/_cluster/health
curl -fsS http://localhost:9200/_cat/indices/siem-events-*?v
curl -fsS http://localhost:9200/_cat/indices/siem-alerts?v
```

确认模板先于新事件写入；mapping 冲突时创建新日期索引并应用模板，不要在线修改已有字段类型。备份恢复只使用 `infra/elasticsearch/backup-restore-rehearsal.sh` 的临时索引流程。

## 端到端冒烟路径

1. 在控制台创建或启用一个数据源，确认对应配置出现在 `infra/log-sources/`。
2. 向该数据源端口发送一条可解析日志，例如：

   ```bash
   echo 'Aug 22 17:17:28 codex-e2e-0822 sshd[8421]: Failed password for codexuser1 from 198.51.100.247' | nc -w1 localhost 5000
   ```

3. 在 Elasticsearch 查询 `siem-events-*`，确认 `@timestamp`、`source.ip`、`event.outcome` 等字段。
4. 检查 Kafka offset 与 Flink 作业，再查询 `siem-alerts`；窗口规则需要达到阈值才会生成告警。
5. 在告警台完成状态、verdict、备注和建案操作，最后在审计日志中确认真实操作者。
6. 若已发布 SOAR Playbook，查询 `/api/soar/executions`，确认对应 `alert.created/updated` 自动产生执行；Human 节点到 `/soar/approvals` 处理。

## 常见现象与处理

| 现象 | 优先检查 | 处理原则 |
| --- | --- | --- |
| 页面空白 | 浏览器控制台、Vite/API 地址、`web` 构建 | 先确认 API 返回非 5xx，再重启 Vite；不要用空数据掩盖初始化异常 |
| 健康扫描 Kafka DOWN | listener、broker 选主、topic 分区 | 先修连接和元数据，再重试扫描 |
| Logstash 仅显示 degraded | 容器内 9600 API、pipeline 日志 | 宿主机不可达时按降级语义处理 |
| 有事件无告警 | Kafka offset、Flink `RUNNING`、规则 `enabled` | 区分事件已入 ES 与检测链路已消费 |
| 规则发布后作业下线 | Flink savepoint/cancel/submit 日志 | 保留旧配置，按部署脚本恢复；不要手工改运行容器 |
| 数据源停用后仍轮询 | 控制面状态、后台任务、端口和 pipeline | 等待任务收敛；重复操作前检查任务 ID 和审计记录 |
| SOAR 无执行 | Playbook 是否 published+enabled、两个 lifecycle topic、`siem-soar-runtime` lag | 不检查 `siem-events`，先确认 lifecycle 消息和字典/发布门禁 |
| SOAR 长时间 waiting | `next_run_at`、Worker 指标和数据库时钟 | Wait 到期才会领取；不要通过重启服务跳过等待 |
| SOAR waiting_human | `/soar/approvals` 是否存在 pending 记录 | 批准/拒绝后沿显式分支恢复，不新建执行 |

## 变更、备份与回滚

- 代码、Compose、Logstash、ES 模板和规则以 Git 工作区为准；运行容器不是配置源。
- 修改数据源、规则或模板前记录任务 ID、旧版本和健康扫描结果。
- 任何涉及 ES mapping、Kafka 分区、Flink 作业的变更，都要补自动测试和运行态验证，并在失败时保留旧配置。
- 生产环境启用认证/TLS、多个 broker、RF≥2、快照存储和明确的 RTO/RPO 后，才可把“开发/演示可用”升级为“生产可用”。
