# Story 06 — 系统设置·资产关键度

> **元信息**
> - 关联模块:08 产品设计 §5.6 系统设置
> - 优先级:P2
> - 状态:草稿
> - 依赖:实体风险聚合已实现(entity-risk.py + asset-criticality.json)

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 在设置页编辑资产关键度(IP/用户/主机 → Low/Medium/High/Extreme),
**以便** 实体风险聚合按资产重要度加权,让"域控上的 Medium"不被"实验室机器上的 High"盖过。

## 2. 背景与目标

### 2.1 背景
- `asset-criticality.json`(权重 0.5/1/1.5/2)已存在,被 `entity-risk.py` 读取。
- 缺:可视化管理界面(现在要手改 JSON)。

### 2.2 目标
- 设置页可视化增删改资产关键度,保存后实体风险聚合读取新权重。
- 对齐 Elastic 的资产关键度模型(Low 0.5 / Medium 1 / High 1.5 / Extreme 2)。

### 2.3 非目标
- 不做多租户/角色化设置。
- 不做资产自动发现。

## 3. 用户旅程

```
① 打开系统设置 → ② 资产关键度 → ③ 检索/新增/修改资产 → ④ 保存 → ⑤ 实体风险聚合用新权重
```

| 步骤 | 操作 | 反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ③ | 新增 IP/用户 → 选级别 | 列表更新 | 重复项→提示覆盖 |
| ④ | 保存 | 写回配置 | 保存失败→回滚 |
| ⑤ | 触发实体风险 | 风险分变化 | — |

## 4. 需求明细

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 资产关键度 CRUD(按 IP/用户/主机) | P2 | 级别→权重映射 |
| FR-2 | 保存并应用到实体风险聚合 | P2 | entity-risk.py 读最新配置 |
| FR-3 | 检索资产 | P2 | 按 IP/名称模糊 |

## 5. 后端架构

```
前端(设置) → Spring Boot API(/api/settings/criticality) → asset-criticality.json
```

### 5.1 组件
| 组件 | 职责 |
| --- | --- |
| `CriticalityService`(新) | 资产关键度 CRUD,读写 JSON |
| 存储 `asset-criticality.json` | 已有 |

### 5.2 API 契约
```
GET  /api/settings/criticality            → 200 {ip:{}, user:{}, host:{}}
PUT  /api/settings/criticality/{type}/{key} {level} → 200
DELETE /api/settings/criticality/{type}/{key}      → 204
```

## 6. 数据流实现

```
① 设置页 → CRUD API → 写 asset-criticality.json
② 实体风险聚合(entity-risk.py / 后续 alert-service)→ 读该 JSON → 加权聚合
```

## 7. 验收标准

- **正常**:**Given** 设置页为 `10.0.0.1` 设 Extreme **When** 保存并跑实体风险聚合 **Then** 该 IP 风险分 ×2。
- **异常**:**Given** 保存时 JSON 写入失败 **When** 提交 **Then** 提示失败,配置不破坏。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [Elastic 实体风险评分](https://www.elastic.co/docs/solutions/security/advanced-entity-analytics/entity-risk-scoring) | 资产关键度权重模型 |

## 9. 开放问题

- 关键度默认值、批量导入(从资产清单 CSV)。
