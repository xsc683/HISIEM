# 仓库约定

## 项目结构与模块组织

Spring Boot 代码组织为 `modules/` 下的 Maven 模块与 `applications/` 下的应用组合根；每个模块在自己的
`src/main/java/` 与 `src/test/java/` 目录树下镜像其包结构。`flink/` 是检测作业的独立 Maven 模块，入口
是 `com.siem.DetectionJob`，测试在 `flink/src/test/java/` 下。Vue 3/Vite 控制台在 `web/`。保持
`web/src/App.vue` 是一个薄根组件，真实路由定义在 `web/src/router/index.js`，HTTP 行为集中在
`web/src/api/index.js`，业务页面拆分到 `web/src/views/<module>/` 下。把 `infra/` 当作 Docker Compose、
Logstash pipeline、Elasticsearch 模板、检测规则 YAML 与部署脚本的唯一来源。架构与运维决策写在
`docs/`。不要提交生成物 `target/`、`web/dist/` 或 `web/node_modules/`。

## 构建、测试与开发命令

在仓库根目录、用 Java 21 执行。本文件与 [`CLAUDE.md`](CLAUDE.md) 需保持同步——两者面向不同的 AI 编码
工具，内容以同一套事实为准。

```bash
./mvnw test                            # test the whole Spring Boot reactor
./mvnw -pl applications/control-api spring-boot:run   # start the control API on port 8080
./mvnw -f flink/pom.xml clean package  # test and build the shaded Flink JAR
npm --prefix web ci                    # install the locked frontend dependencies
npm --prefix web run dev               # start Vite on port 5173
npm --prefix web run build             # create the production frontend bundle
```

Windows 上用 `mvnw.cmd`。`wsl bash /mnt/d/Project/SIEM/infra/deploy.sh` 只用于集成部署；先看
`docs/operations/deployment.md`。

## 代码风格与命名约定

Java 使用仓库的 Spotless 检查配 Google Java Format AOSP 风格；包名小写、类型 `PascalCase`、成员
`camelCase`。保持 controller 薄，行为放在 feature service/store 里。前端沿用既有 Vue 风格：
Composition API、两空格缩进、单引号、不加分号、`.vue` 组件 `PascalCase`、API/composable 函数
`camelCase`。列表、表单、详情应保持为各自独立的路由；不要把跨页状态挪进根布局。YAML 用两个空格与
kebab-case 标识符，例如 `rule-ssh-brute-force-001`。

## 测试约定

测试使用 JUnit 5、Mockito 与 Flink operator test harness。类名用 `*Test`，方法名按可观测行为命名，
例如 `create_duplicatePort_conflict409`。行为变更要补成功路径与失败路径测试。每个提议的改动都必须
写明它的验证命令、预期结果，以及回滚或可观测性说明。改动共享 schema 或检测规则时要跑两套 Maven
测试。没有配置覆盖率数值门禁。

## 提交与 Pull Request 约定

历史遵循 Conventional Commit 风格前缀，主要是 `feat:`、`fix:` 与 `docs:`，其后跟一句简明的祈使式
摘要。Pull Request 应说明范围与运维影响、关联相关 issue/story、列出验证命令，并对控制台改动附截图。
schema、索引模板、规则、端口或部署改动要显式点出；绝不提交凭据或本地运行期状态。
