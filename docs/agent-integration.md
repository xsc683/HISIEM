# HISIEM-Agent 启动集成

当前集成只负责从 HISIEM 的告警详情和案件详情创建 Agent 调查任务。调用链为：

```text
HISIEM Browser
  → HISIEM POST /api/alerts/{id}/agent-investigation
  → HISIEM-Agent POST /api/v1/runs
  → run_id
  → Agent UI /ui/runs/{run_id}
```

浏览器只访问 HISIEM API。Agent 地址和可选的服务端 Bearer 凭据由 SIEM 进程读取，
不会进入前端 bundle、URL 或响应错误正文。

## 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `HISIEM_AGENT_BASE_URL` | `http://127.0.0.1:8000` | SIEM 服务端调用 Agent API |
| `HISIEM_AGENT_UI_BASE_URL` | 与 `HISIEM_AGENT_BASE_URL` 相同 | 浏览器成功后跳转的 Agent UI 地址 |
| `HISIEM_AGENT_BEARER_TOKEN` | 空 | 可选的服务端 Agent 凭据 |
| `HISIEM_AGENT_TIMEOUT` | `PT10S` | 创建任务的 HTTP 超时 |

Agent 创建请求只包含 `task_type`、固定的调查 prompt、`provider=hisiem` 的
`ResourceReference`、`requested_by` 和启动来源 metadata。告警或案件正文不会复制到
请求或 URL；Agent 应通过自己的 HISIEM provider 重新 hydrate 权威数据。

两个启动端点都要求已认证的 `ADMIN` 或 `ANALYST`，租户来自 HISIEM 已完成成员校验的
`TenantContext`，启动人来自当前 Spring Security principal。上游 Agent 的 4xx 会归一化为
`502 AGENT_REJECTED`，连接/5xx 会归一化为 `503 AGENT_UNAVAILABLE`。

## 本阶段边界

这是启动代理，不是 HISIEM-Agent runtime、SOAR 执行器或数据同步器。当前 Agent API 的
服务间认证策略仍应由部署网络和后续认证能力共同约束；该集成已经保证服务凭据不暴露给
浏览器，并为后续服务间认证保留了服务端 Authorization 配置位置。
