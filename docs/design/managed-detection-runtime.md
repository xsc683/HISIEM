# Managed detection 运行时 — Phase 5A 与 5B

## 范围

Phase 5A 提供独立的 detection controller 地基。`detection-control` 依然是被控制 API 使用的期望状态与
观测状态库；`detection-controller` 是一个独立的 `WebApplicationType.NONE` Spring Boot 进程，它领取
group 并经由 `detection-runtime` 里传输中立的契约对它们做对账。

Phase 5B 增加针对所配置 Flink JobManager 容器的可选 process adapter。controller 依据确切的
assignment、plan 与 revision 行物化出一个租户/group 作用域的不可变产物，以结构化身份提交 Flink 作业，
并且只从真实 Flink 作业列表加上本地产物 manifest 接受观测成员。期望 manifest 永不被复制进观测状态。

控制 API 没有 Detection 进程/部署命令权限，也不调用 Docker/WSL 命令或 Flink CLI。它的 deploy 端点只
持久化期望状态并返回 `202 PENDING`；controller 的 claim 是从 Detection 期望状态走到运行时端口的唯一
路径。既有的非 Detection Logstash 与 criticality 运维出于兼容仍可经
`platform-operations-adapters` 使用，默认启用，可用 `app.operations.process-adapters=disabled` 关闭。

## 持久状态与 fencing

V18 给 `detection_job_group` 增加了 controller 专属字段：

- `reconcile_state`：`PENDING`、`INSPECTING`、`APPLYING`、`VERIFYING`、`IDLE` 或 `FAILED`；
- `reconcile_available_at` 与 `reconcile_attempts`，用于到期轮询与有界指数退避；
- `controller_lease_owner`、`controller_lease_until` 与单调的 `controller_fencing_token`；
- `last_reconciled_at`。

`claimDue` 在一个事务里用 `FOR UPDATE SKIP LOCKED`，只领取已到期、且没有活跃租约的行——除非一个新
期望的 `PENDING` generation 取代了那个租约——并递增 fencing token 与尝试次数，同时快照期望 generation
与期望 manifest。心跳、阶段、失败与释放更新都要求 owner + token + 期望 generation。过期更新返回
`false`；运行时 `status`、`job_id` 与 `job_key` 不被 controller 仓储改动。

## 对账契约

`DetectionReconciler` 校验 manifest JSON、确切的 租户/group/cluster 作用域、generation 与 SHA-256
哈希。它在做决定之前先 inspect：

- 非空的期望 manifest 只在 `RuntimeDiff` 或作业状态不匹配时才 apply；
- 空的期望 manifest 会调用 `stop`，除非 inspect 到的运行时已经是 `STOPPED` 且没有成员；
- 每一次外部调用与每一个 observe 边界都检查租约；apply/stop 之后跟着一次 VERIFYING inspect；
- 只有 `DetectionRuntimeService.observe` 写入那一份唯一的观测状态；
- generation 或 fencing 变化会放弃这次尝试，并且永不把它作为成功释放；
- adapter 失败进入 `FAILED`，配封顶的指数退避。

worker 每轮最多领取 100 个 group，但一次只领取并完整处理一个租约，好让每个已领取租约都有活跃心跳。
每个租约按其时长的三分之一续租，心跳执行器在 `PreDestroy` 时关闭。

## Adapter 模式、不可变产物与作业身份

默认的 `app.detection.runtime-adapter=disabled` 启用 `DisabledFlinkRuntimePort`。它不做物理部署，报
`UNKNOWN`；这依然是安全的开发默认值。设 `app.detection.runtime-adapter=process` 恰好启用一个
`ProcessFlinkRuntimeAdapter`；条件化配置与 disabled adapter 互斥。process adapter 只接受它所配置的
`app.detection.cluster-id`，而在提供了白名单时，该值还必须出现在 `app.detection.allowed-clusters` 里。

`DetectionArtifactBuilder` 把产物存到：

```text
<app.detection.artifact-root>/<jobKey>/<generation>-<manifestHash>/
  runtime-manifest.json
  artifact-metadata.json
  0001-<safe-rule-slug>.yaml
```

manifest 文件是 `RuntimeManifestCodec.canonicalSpecJson(expected)` 的确切 UTF-8 字节，而它的原始
SHA-256 必须等于期望哈希。构建器用确切的租户与 group 谓词查询 join 了 plan 与 revision 的
assignment，校验每一条规则修订、plan 哈希、generation 与成员集合，写入临时目录，然后原子地移到目标
位置。既有目录在复用之前会被完整重新校验；畸形的规则键无法逃出产物根目录。产物目录不可变，且至少应
保留到最长回滚/savepoint 窗口加上调查留存期那么久。

稳定键是 租户、集群与 group 的长度分隔 SHA-256 身份，渲染为 `dg-<24 位小写十六进制>`。被托管的作业
名是：

```text
SIEM-DETECTION-dg-<24hex>-g<generation>-m<64hex>
```

这个名称不含任何原始租户或 group 字符串。process adapter 只通过参数向量调用 Docker/Flink
（`docker exec ... flink list -a`、`cancel -s`、`run -d`），永不通过 shell 拼接。它从「解析出的作业
身份所指的本地产物」读取观测 manifest，把缺失/损坏/作用域不匹配的产物判为 `UNKNOWN`，拒绝一个键上
存在重复活跃作业，并且只停掉携带目标键的作业。更新使用 cancel 返回的 savepoint；一次失败的替换会用旧
产物与同一个 savepoint 尝试回滚，而如果回滚也失败，则保留最初的失败信息。

Flink 的托管启动会收到 `rulesDir`、`jobKey`、`generation` 与 `manifestHash` 作为类型化参数。启动时它
对原始 `runtime-manifest.json` 取哈希，校验 schema/作用域/generation，并要求 manifest 的规则键集合
等于 `RuleConfigLoader` 实际加载到的那些唯一 ID。被托管的 Kafka group 与 checkpoint/savepoint 路径
按作业键隔离。旧式启动只通过一条显式的兼容路径继续受支持，并会记录「manifest 校验被跳过」。

## 运维与限制

只在独立的 controller 进程里启用 process adapter，并且是在授予它「为所配置容器调用 Docker、读本地
产物根目录、写所配置 savepoint 位置」的权限之后。不要在 `control-api` 里启用它。这个 adapter 是单
集群的进程实现：生产级 HA、多集群编排、分布式产物存储/加锁，以及全自动的灾难恢复策略，都不是
Phase 5B 所声称的。

把 controller 与控制 API 分开运行：

```bash
./mvnw -pl applications/detection-controller spring-boot:run
```

两个应用都消费 `modules/platform-migrations` 里那唯一一棵迁移树。
