# 部署指南(新机器/换环境)

> 定位：新环境、重建和升级的操作权威。日常启动、健康扫描和故障处理见 [`operations.md`](operations.md)，当前验证结论见 [`current-status.md`](current-status.md)。
>
> 本仓库是**唯一来源**,所有基础设施配置、代码、文档都在这。部署环境(旧 PC 的 WSL2)失效后,按本文档可在新机器完整重建。

## 1. 环境前提

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Windows | 10/11 | 宿主系统 |
| WSL2 + Docker Desktop | — | 给 WSL 分配足够内存(建议 ≥16G,ES 占 4G heap) |
| PostgreSQL | 16 | Compose 提供 `localhost:5432/siem`，控制面由 Flyway 自动建表 |
| Java | 21 | 构建 Spring Boot / Flink |
| Maven | 3.9+ | 构建(或用仓库 `./mvnw`) |
| Python3 | 3.10+ | 跑 Kibana 创建脚本 |

> 旧环境硬件参考:12600KF + 32G,WSL 分 8c24g。

## 2. 克隆仓库并定位

```bash
# WSL 内建议部署目录(与 deploy.sh 默认一致)
mkdir -p ~/projects/mini-siem
# 仓库在 Windows 侧路径:D:\Project\SIEM,即 WSL 内 /mnt/d/Project/SIEM
```

## 3. 一键同步 + 构建(可选,或手动)

`infra/deploy.sh` 把仓库同步到 WSL 部署目录,并构建 Flink jar、拷贝进容器:

```bash
# 从 Windows 仓库根执行(WSL 内则去掉 wsl 前缀和 MSYS_NO_PATHCONV=1)
MSYS_NO_PATHCONV=1 wsl bash /mnt/d/Project/SIEM/infra/deploy.sh --start-job
```

`deploy.sh` 做的事:
1. 同步 `infra/docker-compose.yml` → `~/projects/mini-siem/`
2. 同步 `infra/logstash/` → `~/projects/mini-siem/logstash/`(rsync 原地同步,**不可 rm -rf**,见设计决策)
3. 同步 `flink/` → `~/projects/mini-siem/flink/`
4. `docker compose config` 校验并启动服务,按 healthcheck 等待 PostgreSQL/ES/Kafka/Logstash/Flink/Kibana
5. WSL 内 `mvn clean package` 构建 jar,拷贝到 JobManager,并原地重建规则目录
6. `--start-job` 下仅当检测作业不存在时提交,避免重复运行

Compose 项目名固定为 `infra`，这样 `container_name`、卷和手动启动保持一致；
Elasticsearch 备份卷会在启动前由一次性初始化服务修正为 ES 用户可写。
`infra/elasticsearch/config/elasticsearch.keystore` 是每个部署环境自己的敏感运行态文件，仓库不会提交它；`deploy.sh` 的 rsync 也显式排除该文件，升级代码不会清空目标环境已写入的 secure settings。凭据只能通过部署环境的 `elasticsearch-keystore` 命令或密钥注入流程维护。

Kafka 的默认开发配置使用双监听：容器内的 Logstash/Flink 连接 `kafka:9092`，宿主机上的 API 和运维脚本连接 `localhost:9092`（宿主机端口映射到容器的 EXTERNAL 监听）。不要把 `KAFKA_ADVERTISED_LISTENERS` 改成只有 `kafka:9092`，否则宿主机 Kafka AdminClient 首次连接后会被元数据重定向到 Docker 内部主机名，运行态扫描会报 `Timed out waiting for a node assignment`。

Spring Boot 应用启动时默认读取 PostgreSQL:

```bash
java -jar target/hsiem-platform-0.0.1-SNAPSHOT.jar
# 首次启动且 users 表为空时必须提供一次性临时管理员口令；另可用 SIEM_DB_URL / SIEM_DB_USERNAME / SIEM_DB_PASSWORD 覆盖连接
# PowerShell: $env:SIEM_BOOTSTRAP_PASSWORD = '<至少12位临时口令>'
# WSL: export SIEM_BOOTSTRAP_PASSWORD='<至少12位临时口令>'
```

Flyway 首次启动会创建控制面表并导入旧版本 `infra/auth/users.yaml` 用户；当前迁移为 V12。V11 建立 lifecycle SOAR 的 Playbook/execution 基线，V12 增加 trigger envelope、逐 attempt `soar_node_execution`、`soar_approval_task` 和 `soar_action_receipt`。V8-V10 旧 SOAR 表以及 V11 被替代的 node/approval 表作为历史迁移保留；之后 PostgreSQL 是用户、角色、审计和 SOAR 控制面记录的唯一来源。
登录 Token 只在响应中返回一次，数据库保存 SHA-256 后的会话值；默认会话 8 小时，连续 5 次失败后锁定 15 分钟。控制面 API 需要 `Authorization: Bearer <token>`。首次登录或管理员新建用户必须先调用密码轮换接口，业务 API 在轮换完成前返回 428。

Logstash 的 healthcheck 同时检查 5000/5001/5002/5004/5005/5006 和 9600,
避免出现“容器 healthy 但 pipeline 尚未监听”的假就绪。
> 若 WSL 内没有 Java/Maven,改在 Windows 侧用 IDEA 自带 Maven 构建(见 §11),
> 再 `docker cp /mnt/d/Project/SIEM/flink/target/detection-job-1.0.jar siem-flink-jobmanager:/opt/flink/`。

## 4. 启动基础设施

```bash
# deploy.sh 已包含启动和就绪等待;手动启动时:
cd ~/projects/mini-siem && docker compose up -d
docker compose ps
```

## 5. 创建 Kafka topic

```bash
bash /mnt/d/Project/SIEM/infra/kafka/create-topics.sh
```

> 必需：脚本会创建 `siem-events`、Flink 解析隔离用的 `siem-events-dlq`、`siem-alert-lifecycle`、`siem-case-lifecycle`。apache/kafka:3.8 默认关闭 `auto.create.topics.enable`，而 Flink 订阅 topic
> 的元数据查询不会触发自动建主题。不建的话 Flink job 会因 `UnknownTopicOrPartitionException`
> 反复 RESTARTING。

## 6. 应用 ES 索引模板

```bash
bash /mnt/d/Project/SIEM/infra/elasticsearch/apply-templates.sh
```

> 若 `siem-alerts` 索引已存在旧 mapping,需要删掉重建才能套上新模板:
> `curl -X DELETE http://localhost:9200/siem-alerts`

## 7. 创建 Kibana dashboard

```bash
bash /mnt/d/Project/SIEM/infra/kibana/create-dashboards.sh
# 访问 http://localhost:5601/app/dashboards#/view/dashboard-siem-overview
# 记得把时间范围选大(如 Last 7 days)以看到数据
```

## 8. 提交 Flink 检测 job

```bash
# jar 已在容器 /opt/flink/ 下(deploy.sh 或手动 docker cp)
docker exec siem-flink-jobmanager flink run -d /opt/flink/detection-job-1.0.jar
```

> 更新 job:先 `flink list` 拿到 JobID → `flink cancel <JobID>` → 重新 `flink run`。
> 因为已开 checkpointing + `committedOffsets`,重启**不会重放历史**(不会重复告警)。

## 9. 自动自验证

```bash
bash /mnt/d/Project/SIEM/infra/validate-deployment.sh
```

脚本以非 0 退出表示失败,检查 Compose 配置、7 个容器状态、健康检查、6 个 Logstash 输入端口、PostgreSQL/ES/Kibana/Flink API、三个 Kafka topic 及检测作业 RUNNING。
只启动数据面而未提交 Flink 作业时可用 `REQUIRE_DETECTION_JOB=0`。
Spring Boot 启动并完成 Flyway 后，可追加 `REQUIRE_CONTROL_PLANE_SCHEMA=1` 检查 PostgreSQL 基础控制面表；SOAR V12 Handler/attempt 表由 Flyway/Testcontainers 迁移测试单独校验。

应用接口自验证示例:

```bash
BOOTSTRAP_PASSWORD='<首次启动时设置的临时口令>'
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$BOOTSTRAP_PASSWORD\"}" | jq -r .token)
# 若登录响应 passwordChangeRequired=true，先用 TOKEN 调整为至少 12 位的新口令
curl -s -X POST http://localhost:8080/api/auth/password \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"currentPassword":"<临时口令>","newPassword":"<至少12位的新口令>"}'
curl -s http://localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/tasks -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/ops/health-scan -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/soar/playbooks -H "Authorization: Bearer $TOKEN"
```

`/actuator/health` 公开用于存活探针；`/actuator/metrics`、`/actuator/prometheus` 需要 admin 权限。

### SOAR lifecycle 配置

API 默认从宿主机 `localhost:9092` 消费，Flink 默认从容器网络 `kafka:9092` 发布。可覆盖：

```bash
export SIEM_SOAR_KAFKA_CONSUMER_ENABLED=true
export SIEM_SOAR_KAFKA_GROUP=siem-soar-runtime
export SIEM_ALERT_LIFECYCLE_TOPIC=siem-alert-lifecycle
export SIEM_CASE_LIFECYCLE_TOPIC=siem-case-lifecycle
```

SASL/SSL 使用 `SIEM_KAFKA_SECURITY_PROTOCOL`、`SIEM_KAFKA_SASL_*`、`SIEM_KAFKA_SSL_TRUSTSTORE_*`。SOAR API 使用 `X-Tenant-ID` 选择租户并校验 `tenant_memberships`。当前没有外部 Connector/Vault/mTLS Runner，不需要也不应配置旧 `SIEM_SOAR_PROXY_*` 或 `SIEM_VAULT_*`。

运行态扫描会检查 PostgreSQL、Elasticsearch、Kafka、Logstash、Flink 和 Kibana；Flink/Kibana 校验响应语义，Logstash 优先检查 pipeline API，API 不可用时才返回明确标记为 degraded 的 TCP 结果（当前镜像默认把 9600 绑定在容器回环地址，宿主机看到该提示属于预期降级，不代表 pipeline 停止）。备份恢复演练只操作临时索引：

```bash
bash /mnt/d/Project/SIEM/infra/elasticsearch/backup-restore-rehearsal.sh
```

案件处置可用 `PATCH /api/cases/{id}/metadata` 保存负责人和证据引用；数据源停用使用 `POST /api/log-sources/{id}/deactivate`，完成后可通过 `GET /api/tasks/{id}` 查看阶段进度。

生产环境不能直接使用 Compose 的开发默认值。请先准备 ES HTTPS/凭据、Kafka SASL_SSL 和多节点/RF≥2，再执行：

```bash
REQUIRE_PRODUCTION_SECURITY=1 REQUIRE_CONTROL_PLANE_SCHEMA=1 \
  bash /mnt/d/Project/SIEM/infra/validate-deployment.sh
```

详细的证书、凭据和 truststore 要求见 [`infra/SECURITY.md`](../infra/SECURITY.md)；密钥和证书不提交 Git。

## 10. 验证链路

```bash
# 发一条测试日志
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000

# 查事件(按日志日期)
curl -s "http://localhost:9200/siem-events-2026.08.01/_count"
# 查告警
curl -s "http://localhost:9200/siem-alerts/_count"

# 暴力破解测试(5 条同 IP 失败 + 1 条推进 watermark)
bash /mnt/d/Project/SIEM/infra/simulator/brute-force-test.sh
```

## 11. 常见故障

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| Flink job 一直 RESTARTING,日志报 `UnknownTopicOrPartitionException: siem-events` | Kafka topic 未创建(3.8 默认关 auto-create) | 跑 `create-topics.sh` 建 topic 后重启 job |
| Logstash 容器 exit 127 | Docker Desktop bind mount 注册失效(曾对挂载目录 rm -rf) | 重启 Docker Desktop 清缓存 |
| 重启 job 后告警翻倍 | 用 `earliest()` 忽略已提交 offset | 用 `committedOffsets(EARLIEST)`(已是默认) |
| Logstash 配置报 Unknown setting 'naming_strategy' | 该选项在 Logstash 8.14 不存在 | 移除(仓库已无此配置) |
| Kibana dashboard 报 searchSourceJSON undefined | dashboard 对象缺 `kibanaSavedObjectMeta.searchSourceJSON` | 用 create_dashboards.py 重建 |
| WSL 跑 `.sh` 报 `set: pipefail: invalid option name` | 脚本 CRLF 换行(仓库已强制 `*.sh` 用 LF) | 重新 checkout 或 `sed -i 's/\r$//'` |
| 单机 24G 内存吃紧 | ES 4G + Flink TM | 调低 ES_JAVA_OPTS 或加内存 |
| Logstash healthcheck 超时 | pipeline 配置或 ES/Kafka 依赖未就绪 | `docker compose logs logstash`,确认 5000-5006 均监听 |

## 12. 构建命令参考

Windows 侧用 **IDEA 自带的 Maven**(已验证:3.9.16 + Java 21,依赖已缓存到 `C:\Users\1\.m2\repository`),
绕开 `./mvnw`(bash 版)在 Windows 用 curl 下载 Maven 时的 schannel 证书吊销报错
(`CRYPT_E_REVOCATION_OFFLINE`)。在 Git Bash 里执行:

```bash
MVN="D:/application/IntelliJ IDEA 2026.2.0.1/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"

# Flink 检测 job —— 注意用 -f flink/pom.xml(根 pom.xml 是 Spring Boot 控制面,不是它)
"$MVN" -f flink/pom.xml clean package          # 测试数量以 Maven 输出为准
"$MVN" -f flink/pom.xml clean package -DskipTests   # 部署时加快

# Spring Boot 控制面（根 pom；Flyway 当前 V12，测试数量以 Maven 输出为准）
"$MVN" -f pom.xml clean package
```

> 备选:`mvnw.cmd`(.cmd 版 wrapper)下载走 PowerShell,也能绕开 curl 的 schannel 问题;
> 或在 IDEA 里直接把 `flink/` 作为 Maven 工程打开,点 Build 即可(IDEA 默认就用自带 Maven)。
