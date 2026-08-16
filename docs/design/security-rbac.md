# ES 安全与最小权限 RBAC

> 状态:Phase 3.4 · 2026-08-16
> 记录启用 ES 安全(basic auth + RBAC 最小权限)的完整步骤。当前 lab 默认 `xpack.security.enabled=false`,
> **本步骤需在维护窗口执行**,启用后所有组件都要带凭据,否则管道中断。

## 1. 为什么

- 当前 `xpack.security.enabled=false`,9200 无认证,任何能访问该端口的人都能读写。
- 单机 lab 可接受,但暴露到网络前必须启用;且最小权限(非 elastic 超管日常操作)是业界基线。

## 2. 启用步骤

### 2.1 开启安全(改 compose)

`infra/docker-compose.yml` 的 elasticsearch 服务:

```yaml
environment:
  - discovery.type=single-node
  - xpack.security.enabled=true      # 由 false 改为 true
  - ELASTIC_PASSWORD=<超管密码>       # 新增:elastic 用户密码
  - ES_JAVA_OPTS=-Xms4g -Xmx4g -Dpath.repo=/usr/share/elasticsearch/backups
```

`docker compose up -d elasticsearch` 重建后,ES 强制 TLS + 认证。

> 注:若需保留 9200 明文,可把 `xpack.security.http.ssl.enabled` 设为 false(仅内网),并在各组件用 http + 用户名密码。

### 2.2 创建角色与用户

```bash
# 建 siem_ingest / siem_analyst 角色 + logstash_writer / siem_analyst_user 用户
LOGSTASH_PASSWORD=xxx ANALYST_PASSWORD=yyy bash infra/elasticsearch/setup-rbac.sh <elastic密码>
```

### 2.3 组件接入凭据(启用后必须)

| 组件 | 改动 |
| --- | --- |
| Logstash | elasticsearch output 加 `user => "logstash_writer"` `password => "xxx"` |
| Flink | ES sink:`Elasticsearch8AsyncSinkBuilder` 加 `.setUsername(...)` / `.setPassword(...)`(或 REST client auth) |
| Kibana | compose 加 `ELASTICSEARCH_USERNAME=elastic` `ELASTICSEARCH_PASSWORD=<超管密码>` |
| 运维脚本 | apply-templates.sh / create_dashboards.py / backup.sh / triage-alert.py 的 curl/python 请求加 `-u user:pass` / Authorization 头 |

## 3. 权限模型(最小权限)

| 角色 | 授予 | 说明 |
| --- | --- | --- |
| `siem_ingest` | siem-events-* / siem-alerts 的 create_index/index/write/manage | 给 Logstash 写入 |
| `siem_analyst` | siem-events-* / siem-alerts 的 read/view_index_metadata | 给分析师查询/Kibana |
| elastic | 超管 | 仅初始化/排障,不做日常操作 |

> 多租户:按 index name pattern 分角色;敏感字段(如用户 IP)可用 FLS(field_security)隐藏。

## 4. 当前状态与风险

- **lab 默认不启用**(避免管道中断);启用前请先完成 2.3 的全部组件凭据接入。
- 若误启用导致 Logstash/Flink 写失败,症状是 `siem-events` / `siem-alerts` 停止新增——回退 `xpack.security.enabled=false` 重建即可。
