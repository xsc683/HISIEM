# Story 02 — 解析模板库与自定义解析

> **元信息**
> - 关联模块:08 产品设计 §5.2 解析规则库
> - 优先级:**MVP**(模板选择)/ P1(自定义解析)
> - 状态:草稿
> - 依赖:Story 01(接入向导引用模板库)

---

## 1. 用户故事

**作为** 安全管理员,
**我希望** 从解析模板库选择现成模板、或在库中没有时用样例日志自定义解析规则,
**以便** 接入新日志源时"选而非写",且自定义规则可复用、可测试。

## 2. 背景与目标

### 2.1 背景
- 已有首个模板 `ssh-auth`(infra/parser-templates)+ 后端模板加载/解析测试 API + 前端"选模板/测试"。
- 缺:模板库浏览/检索 UI、自定义解析(保存新模板)、模板测试可视化。

### 2.2 目标
- 接入新日志源时能从模板库选到合适模板;库中没有时能自定义并保存(experimental)。
- 每个模板可被样例验证,有正负样本。

### 2.3 非目标
- 不做模板版本管理的完整 Git 工作流(先文件 + 状态字段)。
- 不做模板市场/远程共享。

## 3. 用户旅程

```
① 打开解析规则库 → ② 浏览/检索(按协议/来源) → ③ 选模板查看详情 → ④ 用样例测试
                                                            ↘ 无合适模板 → ⑤ 自定义解析(样例→grok→预览→保存)
```

| 步骤 | 操作 | 反馈 | 异常/边界 |
| --- | --- | --- | --- |
| ② | 检索"ssh"/"auth" | 模板卡片列表 | 空结果→提示自定义 |
| ③ | 点模板详情 | 模式/ECS/样本展示 | — |
| ④ | 粘贴样例点测试 | 字段预览 | 不匹配→提示 |
| ⑤ | 自定义:贴样例→编 grok→预览→保存 | 实时字段预览 | grok 语法错→红提示 |

## 4. 需求明细

| ID | 需求 | 优先级 | 说明 |
| --- | --- | --- | --- |
| FR-1 | 模板库浏览/检索(按协议/关键词) | MVP | 模板卡片:名称/协议/状态 |
| FR-2 | 模板详情(模式/ECS/actions/样本) | MVP | 可测试 |
| FR-3 | 自定义解析:样例→grok 编辑→即时预览→保存为模板 | P1 | 保存为 experimental |
| FR-4 | 模板校验(正负样本跑通才允许保存) | P1 | 类 CI 门禁 |

## 5. 后端架构

```
前端(模板库/编辑器) → Spring Boot API(/api/parser-templates) → infra/parser-templates/*.yaml
                              │
                              └→ GrokTestService(java-grok) → 即时预览字段
```

### 5.1 组件
| 组件 | 职责 |
| --- | --- |
| `ParserTemplateService`(已有,扩展) | 模板 CRUD、检索、保存新模板 |
| `GrokTestService`(已有) | 解析测试(编辑器即时预览) |
| 存储 `infra/parser-templates/*.yaml` | 模板文件(版本化) |

### 5.2 API 契约
```
GET    /api/parser-templates?q=ssh         → 200 [模板列表(过滤)]
GET    /api/parser-templates/{id}          → 200 模板详情
POST   /api/parser-templates               → 201 {id}(自定义保存,experimental)
POST   /api/parser-templates/test          → 200 {ok, fields}(已有)
```

## 6. 数据流实现

```
① 模板库:ParserTemplateService 扫 infra/parser-templates/*.yaml → 列表/详情
② 编辑器:前端贴样例 → POST /test → GrokTestService(java-grok) → 字段预览
③ 保存:前端提交模板 YAML → 校验(正负样本)→ 写入 infra/parser-templates/*.yaml
④ 接入向导:新建数据源时从模板库选到该模板
```

| 环节 | 处理 | 输出 | 异常 |
| --- | --- | --- | --- |
| 检索 | 按协议/关键词过滤 | 模板列表 | — |
| 测试 | java-grok 编译模式 + capture | 字段 Map | grok 编译错→返回错误 |
| 保存 | 校验样本 + 写 YAML | 新模板文件 | 校验失败→拒绝 |

## 7. 验收标准

- **正常**:**Given** 自定义解析编辑器粘贴一条 nginx 日志、编写 grok **When** 点预览 **Then** 显示提取的 `client.ip`/`http.request.method` 等字段。
- **正常**:**Given** 模板通过正负样本校验 **When** 保存 **Then** 模板出现在库中,接入向导可选到。
- **异常**:**Given** grok 模式语法错误 **When** 点预览 **Then** 红提示错误位置,不保存。
- **边界**:**Given** 样例日志有多行/多格式 **When** 测试 **Then** 覆盖所有模式,取第一个匹配。

## 8. 业界参考

| 参考 | 借鉴 |
| --- | --- |
| [QRadar DSM Editor](https://community.ibm.com/community/user/blogs/kajal-sangani/2023/10/16/dsm-parsing-in-qradar) | 基于样例的可视化字段提取 |
| [Elastic Ingest Pipeline simulate](https://docs-v3-preview.elastic.dev/elastic/docs-content/tree/main/solutions/observability/logs) | simulate 测试管道 |
| [Cribl Packs](https://docs.cribl.io/stream/3.4/usecase-syslog/) | 预置解析包 + 样例测试 |

## 9. 开放问题

- 自定义 grok 编辑器:纯文本输入 + 预览,还是模式片段拖拽?(MVP 用文本 + 常用模式片段提示)
- 模板保存的并发/冲突处理(单机 YAML 文件,MVP 不锁)。
