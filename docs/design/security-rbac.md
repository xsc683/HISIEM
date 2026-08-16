# ES 安全与最小权限 RBAC

> 状态:Phase 3.4 · 2026-08-16
> 记录启用 ES 安全(basic auth + RBAC 最小权限)的完整步骤。当前 lab 默认 `xpack.security.enabled=false`,
> **本步骤需在维护窗口执行**,启用后所有组件都要带凭据,否则管道中断。

## 1. 为什么

- 当前 `xpack.security.enabled=false`,9200 无认证,任何能访问该端口的人都能读写。
- 单机 lab 可接受,但暴露到网络前必须启用;且最小权限(非 elastic 超管日常操作)是业界基线。

## 2. 启用步骤

### 2.1 开启安全(改 compose)

`infra/docker-compose.yml` 的 elasticsearch 服务。启用路径二选一:

**路径 A:TLS(默认,推荐)**——`xpack.security.enabled=true` 后 ES 自动启用 TLS + 认证;
CA 证书(`docker cp siem-elasticsearch:/usr/share/elasticsearch/config/certs/http_ca.crt` 取出)
需分发到各组件信任:Logstash output 加 `cacert`,Flink/Kibana 指到该 CA。

```yaml
environment:
  - discovery.type=single-node
  - xpack.security.enabled=true            # 由 false 改为 true
  - ELASTIC_PASSWORD=${ELASTIC_PASSWORD}   # elastic 超管密码从环境变量注入,不写死进 compose/git
  - ES_JAVA_OPTS=-Xms4g -Xmx4g -Dpath.repo=/usr/share/elasticsearch/backups
```

**路径 B:明文(仅内网)**——只开认证、关 TLS,各组件保持 `http://` 但带用户名密码:

```yaml
environment:
  - discovery.type=single-node
  - xpack.security.enabled=true
  - xpack.security.http.ssl.enabled=false  # 关 TLS(仅内网);其余组件用 http + user/pass
  - ELASTIC_PASSWORD=${ELASTIC_PASSWORD}
  - ES_JAVA_OPTS=-Xms4g -Xmx4g -Dpath.repo=/usr/share/elasticsearch/backups
```

`docker compose up -d elasticsearch` 重建后 ES 启用认证。后续所有 `curl`/组件都带凭据(见 2.3)。

### 2.2 创建角色与用户

```bash
# elastic 超管密码只经环境变量注入(不进 git/命令行历史)
export ELASTIC_PASSWORD='<你的超管密码>'
# 建 siem_ingest / siem_analyst 角色 + logstash_writer / siem_analyst_user 用户
LOGSTASH_PASSWORD=xxx ANALYST_PASSWORD=yyy bash infra/elasticsearch/setup-rbac.sh "$ELASTIC_PASSWORD"
```

Kibana 服务账户(独立密码,不再用 elastic 超管;或改用 ES service account token):

```bash
curl -s -u "elastic:$ELASTIC_PASSWORD" -X POST "http://localhost:9200/_security/user/kibana_system" \
  -H 'Content-Type: application/json' \
  -d "{\"password\": \"$KIBANA_SYSTEM_PASSWORD\", \"roles\": [\"kibana_system\"]}"
```

### 2.3 组件接入凭据(启用后必须)

| 组件 | 改动 |
| --- | --- |
| Logstash | elasticsearch output 加 `user => "logstash_writer"` `password => "${LOGSTASH_PASSWORD}"`;路径 A 再加 `cacert` 指向 ES CA |
| Flink | ES sink:`Elasticsearch8AsyncSinkBuilder` 加 `.setUsername(...)` / `.setPassword(...)`(或 REST client auth) |
| Kibana | compose 加 `ELASTICSEARCH_USERNAME=kibana_system` `ELASTICSEARCH_PASSWORD=${KIBANA_SYSTEM_PASSWORD}`(独立密码,不用 elastic 超管;或 service account token) |
| create_dashboards.py | **Kibana(5601)认证**:python urllib 加 Basic Auth(用分析师账号或 Kibana 服务账户) |
| apply-templates.sh / backup.sh / triage-alert.py | **ES(9200)认证**:curl 加 `-u user:pass`;python urllib 加 Basic Auth 头 |
| entity-risk.py | **ES(9200)认证,直连 9200 读写 siem-alerts / siem-entity-risk**(urllib),需加 Basic Auth,示例见下 |

> **认证端点区别**:create_dashboards.py 访问的是 Kibana(5601),用 Kibana 侧账号;其余脚本(apply-templates.sh / backup.sh / triage-alert.py / entity-risk.py)访问 ES(9200),用 ES 侧账号。
>
> **entity-risk.py 加 Basic Auth 示例**(改 `infra/elasticsearch/entity-risk.py`:统一封装带 `Authorization` 的 `request()` 辅助函数,`api()`(读 siem-alerts)与 `_bulk`(写 siem-entity-risk)**两处请求点都走它**):
>
> ```python
> import base64
> # entity_risk 专用角色用户(与 siem_analyst 分离,见 §3);写库不用 analyst 账号
> AUTH = "Basic " + base64.b64encode(b"entity_risk_user:<ENTITY_RISK_PASSWORD>").decode()
>
> def request(method, path, body=None, content_type="application/json"):
>     """统一带 Authorization 的请求辅助函数:api() 与 _bulk(写库)两处请求点都走它"""
>     data = body.encode() if isinstance(body, str) else (json.dumps(body).encode() if body is not None else None)
>     req = urllib.request.Request(ES + path, data=data, method=method,
>                                  headers={"Content-Type": content_type, "Authorization": AUTH})
>     with urllib.request.urlopen(req) as resp:
>         return json.loads(resp.read().decode() or "{}")
>
> def api(method, path, body=None):       # 读 siem-alerts
>     return request(method, path, body)
>
> def bulk_write(actions):                # 写 siem-entity-risk(_bulk,data 为 NDJSON 字符串)
>     ndjson = "".join(json.dumps(a, ensure_ascii=False) + "\n" for a in actions)
>     return request("POST", "/_bulk", ndjson, content_type="application/x-ndjson")
> ```
>
> 注意:entity-risk.py 读 siem-alerts、写 siem-entity-risk;`entity_risk` **专用角色用户与 `siem_analyst` 分离**——`siem_analyst` 只有 siem-alerts 读权限、不用于写库,写 siem-entity-risk 由 `entity_risk` 角色承担;该角色需补 `{"names": ["siem-entity-risk"], "privileges": ["create_index", "write"]}`(见 §3)。

### 2.4 组件重启顺序与冒烟验证

按依赖序逐个重启,每个验证通过再进下一步(否则定位困难):

1. `docker compose up -d --force-recreate elasticsearch` —— ES 先带认证起,`_cluster/health` 能带 `-u` 通。
2. `docker compose restart logstash` —— output 带凭据后重连 9200;发一条测试日志确认事件入库。
3. `docker compose restart flink-jobmanager flink-taskmanager` → `docker exec siem-flink-jobmanager flink list` 确认 job 恢复(或重新提交 detection job)。
4. `docker compose restart kibana` —— 用 `kibana_system` 连 ES,5601 能登录即通。

冒烟验证(全部带凭据):

```bash
curl -u elastic:"$ELASTIC_PASSWORD" -s "http://localhost:9200/_cluster/health"
curl -u elastic:"$ELASTIC_PASSWORD" -s "http://localhost:9200/siem-events-*/_count"
echo 'Aug 1 10:20:00 server03 sshd[9999]: Failed password for test from 172.16.1.20' | nc -w1 localhost 5000
# 稍后重查 _count 应 +1,Logstash→ES 写链路 OK
```

`_cluster/health` 应为 green(单节点 replica=0);`siem-events-*/_count` 能返回 count 即读写通路正常。

## 3. 权限模型(最小权限)

| 角色 | 授予(ES) | Kibana 空间权限 | 说明 |
| --- | --- | --- | --- |
| `siem_ingest` | siem-events-* / siem-alerts 的 create_index/index/write/manage | — | 给 Logstash 写入(无 UI 需求) |
| `siem_analyst` | siem-events-* / siem-alerts 的 read/view_index_metadata | `kibana_admin`(SIEM 空间 `all`)+ `.kibana` 读 | 给分析师查询/Kibana |
| `kibana_system` | — | 服务账户(Kibana 连 ES 专用) | 独立密码或 service account token,不用 elastic 超管 |
| `entity_risk`(可选) | siem-alerts 的 read + siem-entity-risk 的 create_index/write | — | entity-risk.py 专用(见 2.3 注) |
| elastic | 超管 | — | 仅初始化/排障,不做日常操作 |

> **分析师 Kibana 访问**:`siem_analyst` 的 ES 角色之外,还需在 Kibana 建角色(如 `siem_analyst_kibana`)给 `kibana_admin` 或 SIEM 空间 `all` + `.kibana` 读权限,分析师登录后进入 SIEM 空间。
> **多租户**:按 index name pattern 分角色;敏感字段(如用户 IP)可用 FLS(field_security)隐藏,例如角色里加:
> ```json
> "field_security": { "grant": ["@timestamp", "event.*", "source.ip", "user.name", "alert.*", "threat.*"], "except": ["user.name"] }
> ```

## 4. 当前状态与风险

- **lab 默认不启用**(避免管道中断);启用前请先完成 2.3 的全部组件凭据接入。
- 若误启用导致 Logstash/Flink 写失败,症状是 `siem-events` / `siem-alerts` 停止新增——回退 `xpack.security.enabled=false` 重建即可。
