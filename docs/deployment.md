# 部署指南(新机器/换环境)

> 本仓库是**唯一来源**,所有基础设施配置、代码、文档都在这。部署环境(旧 PC 的 WSL2)失效后,按本文档可在新机器完整重建。

## 1. 环境前提

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Windows | 10/11 | 宿主系统 |
| WSL2 + Docker Desktop | — | 给 WSL 分配足够内存(建议 ≥16G,ES 占 4G heap) |
| Java | 21 | 构建 Spring Boot / Flink |
| Maven | 3.9+ | 构建(或用仓库 `./mvnw`) |
| Python3 | 3.10+ | 跑 Kibana 创建脚本 |

> 旧环境硬件参考:12600KF + 32G,WSL 分 8c24g。

## 2. 克隆仓库并定位

```bash
# WSL 内建议部署目录(与 deploy.sh 默认一致)
mkdir -p ~/projects/mini-siem
# 仓库在 Windows 侧路径,假设 D:\Project\hsiem-platform,即 WSL 内 /mnt/d/Project/hsiem-platform
```

## 3. 一键同步 + 构建(可选,或手动)

`infra/deploy.sh` 把仓库同步到 WSL 部署目录,并构建 Flink jar、拷贝进容器:

```bash
# 从 Windows 仓库根执行(WSL 内则去掉 wsl 前缀和 MSYS_NO_PATHCONV=1)
MSYS_NO_PATHCONV=1 wsl bash /mnt/d/Project/hsiem-platform/infra/deploy.sh
```

`deploy.sh` 做的事:
1. 同步 `infra/docker-compose.yml` → `~/projects/mini-siem/`
2. 同步 `infra/logstash/` → `~/projects/mini-siem/logstash/`(rsync 原地同步,**不可 rm -rf**,见设计决策)
3. 同步 `flink/` → `~/projects/mini-siem/flink/`
4. WSL 内 `mvn clean package` 构建 jar
5. `docker cp` 进 `siem-flink-jobmanager` 容器

## 4. 启动基础设施

```bash
cd ~/projects/mini-siem && docker compose up -d
# 等待所有容器 Up(ES/Kibana/Logstash/Kafka/Flink ×2)
docker ps
```

## 5. 应用 ES 索引模板

```bash
bash /mnt/d/Project/hsiem-platform/infra/elasticsearch/apply-templates.sh
```

> 若 `siem-alerts` 索引已存在旧 mapping,需要删掉重建才能套上新模板:
> `curl -X DELETE http://localhost:9200/siem-alerts`

## 6. 创建 Kibana dashboard

```bash
bash /mnt/d/Project/hsiem-platform/infra/kibana/create-dashboards.sh
# 访问 http://localhost:5601/app/dashboards#/view/dashboard-siem-overview
# 记得把时间范围选大(如 Last 7 days)以看到数据
```

## 7. 提交 Flink 检测 job

```bash
# jar 已在容器 /opt/flink/ 下(deploy.sh 或手动 docker cp)
docker exec siem-flink-jobmanager flink run -d /opt/flink/detection-job-1.0.jar
```

> 更新 job:先 `flink list` 拿到 JobID → `flink cancel <JobID>` → 重新 `flink run`。
> 因为已开 checkpointing + `committedOffsets`,重启**不会重放历史**(不会重复告警)。

## 8. 验证链路

```bash
# 发一条测试日志
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000

# 查事件(按日志日期)
curl -s "http://localhost:9200/siem-events-2026.08.01/_count"
# 查告警
curl -s "http://localhost:9200/siem-alerts/_count"

# 暴力破解测试(5 条同 IP 失败 + 1 条推进 watermark)
bash /mnt/d/Project/hsiem-platform/infra/simulator/brute-force-test.sh
```

## 9. 常见故障

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| Logstash 容器 exit 127 | Docker Desktop bind mount 注册失效(曾对挂载目录 rm -rf) | 重启 Docker Desktop 清缓存 |
| 重启 job 后告警翻倍 | 用 `earliest()` 忽略已提交 offset | 用 `committedOffsets(EARLIEST)`(已是默认) |
| Logstash 配置报 Unknown setting 'naming_strategy' | 该选项在 Logstash 8.14 不存在 | 移除(仓库已无此配置) |
| Kibana dashboard 报 searchSourceJSON undefined | dashboard 对象缺 `kibanaSavedObjectMeta.searchSourceJSON` | 用 create_dashboards.py 重建 |
| 单机 24G 内存吃紧 | ES 4G + Flink TM | 调低 ES_JAVA_OPTS 或加内存 |

## 10. 构建命令参考

```bash
# Flink job(Windows 侧,mvnw)
./mvnw -f flink/pom.xml clean package     # 含测试

# Spring Boot 应用(占位)
./mvnw clean package
```
