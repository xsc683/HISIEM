# SOAR Playbook 可视化编辑基线

> 状态：本基线已经由 Vue 3 控制台实现；完整执行语义、revision 治理和生产边界见 [`../soar.md`](../soar.md)。本文只定义画布交互和最小产品语义，避免把“卡片列表”误称为编排器。

## 1. 项目定位

HISIEM 的 SOAR 是轻量的告警/案件驱动编排：它把已有的告警状态、案件证据、通知和受控 Connector 组合成可恢复流程。它不是完整 Cortex、Splunk SOAR 或任意代码自动化平台，不提供任意 Shell、任意 URL、第三方应用市场和容器级脚本沙箱。

## 2. 最小图模型

- **Start**：画布中的唯一虚拟入口；它的唯一连线映射到后端 `entrypoint`，不作为业务节点持久化。
- **Action**：执行白名单动作，例如更新告警状态、追加案件证据、创建通知或调用登记 Connector。
- **Condition**：后端 `decision` 节点；按条件边选择一个或多个下游。
- **End**：显式返回 `succeeded`、`failed` 或 `rejected`，不能再有输出边。
- **Edge**：有向边，保存 `on` 事件和可选条件；所有边目标必须存在。

一个有效 Playbook 必须有一个 Start 映射、至少一个 End，且所有节点从 Start 可达。除 End 外的每条可达路径都必须继续连接；保存前页面和后端会分别检查未闭合路径。

## 3. 编辑器交互

节点从左侧工具箱拖入 Vue Flow 画布。从节点右侧输出 Handle 拖到另一节点左侧输入 Handle 才会创建边；单击边可编辑 success/failure/approved/rejected 等事件与条件。Start 使用绿色胶囊，End 使用终止胶囊，Action 和 Condition 用不同色带区分。

节点拖动后保存坐标到 revision 的 `layout_json`。业务 DSL 和布局分开持久化，因此调整画布不会改变执行语义。前端校验用于即时反馈，后端 `SoarPlaybookRegistry` 仍是发布前的最终门禁。

## 4. 触发与业务关联

手动执行从告警详情或案件详情携带稳定资源 ID 进入 `/soar`；自动化规则扫描先匹配资源事实，再创建带去重键的执行。Action 只能通过告警、案件、通知服务或 Connector 目录改变外部状态。执行详情展示冻结快照、节点尝试、frontier、父子执行和不可变事件，保证能从自动化动作回到告警/案件调查上下文。

MVP 不以 webhook/cron 完整触发器、应用市场、任意脚本、跨地域调度和全 SIEM 数据面多租户为目标。

## 5. 分期与当前落点

第一阶段是图结构保存、Start/End/连线和双层校验；第二阶段才把图交给持久 Worker 执行动作。本项目当前已经完成两个阶段，并进一步实现审批、延迟、子 Playbook、loop/map、失败边、revision 四眼审批和灰度发布。后续扩展不得绕过已有快照、租约、动作白名单和审计边界。
