# HISIEM ↔ SOC Copilot 启动集成

> **本文件已按当前实现重写（2026-09-24）。** 早期版本描述的是已被替换的旧上游「HISIEM-Agent」接口：
> `POST /api/v1/runs` → `run_id` → Agent UI `/ui/runs/{run_id}`。该形状在当前 HISIEM 代码与
> HISIEM-SOC-Copilot 仓库中均无残留，相关描述已删除。现行上游是 **HISIEM-SOC-Copilot**，
> 使用 `/api/v1/investigations`。逐端点契约见[当前产品契约](product-contract.md)。

当前集成负责从 HISIEM 的告警详情和案件详情创建 Copilot 调查任务，并把调查工作台以只读方式代理进
HISIEM 控制台。调用链为：

```text
HISIEM Browser
  → HISIEM POST /api/alerts/{id}/agent-investigation  (或 POST /api/cases/{id}/agent-investigation)
  → SOC Copilot POST /api/v1/investigations
  → investigation_id
  → 浏览器跳转 {app.agent.investigation-route-base}/copilot/investigations/{investigation_id}
```

浏览器只访问 HISIEM API。Copilot 地址、工作台路由基址和服务端凭据由 SIEM 进程读取，不会进入前端
bundle、URL 或响应错误正文。

## 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `HISIEM_AGENT_BASE_URL` | `http://127.0.0.1:8000` | SIEM 调用 Copilot API 的基址；实际请求 `{base}/api/v1/investigations` |
| `HISIEM_AGENT_WORKSPACE_ROUTE_BASE` | 空 | 启动成功后浏览器跳转的路由基址，拼成 `{base}/copilot/investigations/{id}`；留空时得到站内相对路由，浏览器落在本站 `/copilot/investigations/:investigationId` |
| `HISIEM_AGENT_BEARER_TOKEN` | 空（**必填**） | SIEM → Copilot 的服务凭据。为空时进程直接启动失败，不会发匿名请求；仓库内没有按 profile 放宽该要求的配置 |
| `HISIEM_AGENT_TIMEOUT` | `PT10S` | 调用 Copilot 的 HTTP 超时 |
| `HISIEM_INTERNAL_SERVICE_TOKEN` | 空（fail closed） | Copilot → HISIEM 反向入口 `/api/internal/soar/**` 的服务凭据；未配置时该端点拒绝所有请求 |

## 端点

浏览器只调用 HISIEM 的下列端点；租户与操作人由服务端上下文派生，请求体或请求头无法覆盖：

| 端点 | 作用 | 角色 |
| --- | --- | --- |
| `POST /api/alerts/{id}/agent-investigation` | 从告警详情启动调查 | ADMIN / ANALYST |
| `GET /api/alerts/{id}/agent-investigation` | 告警再进入：查询该来源告警的活动或最近一次调查 | ADMIN / ANALYST / AUDIT |
| `POST /api/cases/{id}/agent-investigation` | 从案件详情启动调查 | ADMIN / ANALYST |
| `GET /api/agent-investigations/{id}` | 调查概览（头部） | ADMIN / ANALYST / AUDIT |
| `GET /api/agent-investigations/{id}/workspace` | 只读工作台读模型（概览 / 证据 / 调查过程 / 时间线 / 响应） | ADMIN / ANALYST / AUDIT |
| `POST /api/agent-investigations/{id}/cancel` | 取消仍在可取消状态的调查 | ADMIN / ANALYST |
| `POST /api/agent-investigations/{id}/response-proposals` | 派生一条类型化响应提案 | ADMIN / ANALYST |
| `POST /api/agent-investigations/response-approvals/{approvalRequestId}/approve` | 人工批准；副作用由 Copilot 的持久化队列异步发起 | ADMIN / ANALYST |
| `POST /api/agent-investigations/response-approvals/{approvalRequestId}/reject` | 人工驳回，不产生执行命令 | ADMIN / ANALYST |

反向入口（Copilot → HISIEM）**不是浏览器接口**，走 `/api/internal/**` 独立安全链，要求
`Authorization: Bearer` 与 `X-Tenant-ID`，任一缺失、错误或凭据未配置都直接 401：

| 端点 | 作用 |
| --- | --- |
| `POST /api/internal/soar/executions` | 把人工批准过的命令提交到 SOAR 持久执行内核（`action_key=START_SOAR_PLAYBOOK`） |
| `GET /api/internal/soar/executions/{executionId}` | 读取执行状态 |

控制台侧的浏览器路由是 `/copilot/investigations/:investigationId`；它没有独立菜单项，入口挂在告警台与
调查台的详情页。

## 请求与响应边界

启动请求体只包含 `source_alert_ref`（`provider` / `resource_type` / `address_id`，其中 `provider` 固定为
`hisiem`），不带 `task_type`、prompt 或告警、案件正文；租户、启动人和幂等键分别走 `X-Tenant-ID`、
`X-Actor-Subject`、`Idempotency-Key` 请求头。Copilot 应通过自己的 HISIEM provider 重新 hydrate 权威数据。

租户来自 HISIEM 已完成成员校验的 `TenantContext`，启动人来自当前 Spring Security principal。Copilot
连接失败或超时归一化为 `503 AGENT_UNAVAILABLE`，5xx 同样落到 `503`。启动路径上 Copilot 的 4xx 归一化为
`502 AGENT_REJECTED`；读取/取消路径另外保留 `404 AGENT_INVESTIGATION_NOT_FOUND`、`403 AGENT_FORBIDDEN`、
`409 AGENT_STATE_CONFLICT`，其余 4xx 同样落到 `502 AGENT_REJECTED`。任何情况都不回显上游响应正文。

## 本阶段边界

这是启动与读取代理，不是 Copilot 的 agent runtime，也不是数据同步器。服务间认证已经落地：SIEM → Copilot
使用必填的 `HISIEM_AGENT_BEARER_TOKEN`；Copilot → HISIEM 使用 `/api/internal/**` 专用安全链，凭据未配置
时 fail closed。仍未闭环的是网络侧约束：该内部入口只依赖凭据强度，不校验来源 IP，也不做网络隔离，持有
凭据的任何调用方都能到达。
