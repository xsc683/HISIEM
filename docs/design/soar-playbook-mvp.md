# SOAR Playbook 画布契约

> 已实现基线。运行时细节见 [`../soar.md`](../soar.md)；本页只定义画布上“能添加什么、怎样连接、参数怎样传”。

## 节点集合

| 节点 | 配置 | 必须连接 |
| --- | --- | --- |
| Start | 无；后端新建草稿时自动生成 | 无入线，一条 `next` |
| End | 无；后端新建草稿时自动生成 | 至少一条入线，无出线 |
| Condition | 1–10 条 AND 条件，字段/操作符来自 `/field-dictionary` | `true`、`false` 各一条 |
| Business | `/action-dictionary` 中的动作和类型化参数 | 一条 `next` |
| Human | 审批提示，可使用模板变量 | `approve`、`reject` 各一条 |
| Wait | 正整数分钟或小时，最多 30 天 | 一条 `next` |

Start/End 唯一且不能删除。画布不显示外部设备、子 Playbook、loop、map、并行网关或事件触发节点，因为后端没有这些语义。

## 连接交互

从节点右侧 Handle 拖到下游左侧 Handle 创建边。普通节点第一条边自动标记 `next`；Condition 依次分配 `true/false`；Human 依次分配 `approve/reject`。分支已经齐全或普通节点已有出线时，前端拒绝继续连接。点击连线可删除后重连。

节点位置直接保存为 `graph.nodes[].x/y`，布局和运行图使用同一 revision，不再维护独立 layout 或虚拟 START。草稿允许暂时未闭合，发布请求由后端执行可达性、终点收敛、分支完整性和 DAG 校验。

## 参数交互

Condition 不能手输字段路径。选定字段后，操作符下拉框只显示该字段兼容项；`is_empty/not_empty` 不显示比较值。

Business 不能手输 action ID。选择动作后按 `/action-dictionary` 生成必填参数表单。参数可以是固定值，也可以引用 `${alert.id}`、`${case.id}` 或 `${nodes.<nodeId>.output.<field>}`。模板路径不存在会使节点失败并写入执行详情，不会静默替换为空字符串。

Human 的批准和拒绝都会恢复同一个执行，并分别走图上的两个显式分支。Wait 不是浏览器倒计时：页面关闭或服务重启不影响 PostgreSQL 中的 `next_run_at`。

## 页面职责

- 列表：`/soar/playbooks`，负责状态、发布、启停和删除；
- 新建：`/soar/playbooks/new`，只收集名称、对象类型和生命周期事件，后端生成合法起始图；
- 编辑：`/soar/playbooks/:id/edit`，自动保存草稿，显式发布；
- 执行：`/soar/executions/:id`，显示冻结图、触发 payload 和节点完整输入/输出；
- 审批：`/soar/approvals`，显示提示、对象、决定人和备注。

UI 校验用于及时反馈，API 的 `SoarPlaybookValidator` 是唯一发布门禁。
