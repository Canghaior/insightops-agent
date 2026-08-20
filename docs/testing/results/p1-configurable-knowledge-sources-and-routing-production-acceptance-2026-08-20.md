# P1.5-B 可配置知识源与动态路由生产验收（2026-08-20）

## 结论

P1.5-B 已完成开发、全量测试、真实数据库门禁、CI 镜像构建、生产部署和生产只读验收。P1.4 RAG 生产验收继续保持关闭状态，三份既有知识库和 Embedding 数据未因本次配置化改造发生回退。

- 生产地址：<https://insightops.canghaior.com/admin/knowledge>
- 功能提交：`0071e837db0ef117180da4227654f5a57fe0317c`
- 启动修复提交：`d108808b3b1c0c4a3fcf3df7e77d9d8c94d1c6a7`
- 功能 CI Run：`32335422527`
- 修复 CI Run：`32336167376`
- 成功生产部署 Run：`32336407366`

## 已交付能力

- 系统管理员可以创建、编辑、启用、停用和删除空知识源。
- 服务端限制公开 HTTPS 来源，校验同域发现 URL、允许路径前缀、URL 规范化和 1～720 小时采集周期；采集网关继续执行 DNS、重定向和运行时 SSRF 防护。
- 已产生文档的来源锁定所属项目、根 URL、允许主机与路径边界，仍可调整名称、来源类型、发现 URL 和采集周期。
- 项目支持 1～720 小时 Release 采集周期和最多 20 个聊天别名。
- Release Worker 和知识采集 Worker 使用各自数据库配置的周期计算下一次执行时间。
- 聊天 Release 工具从当前 Workspace 的已启用项目动态解析仓库名、`owner/repository` 和聊天别名，不再固定为三个 P0 项目。
- 管理页继续实时展示有效页面、已访问/已发现 URL、当前或最后 URL、心跳和租约到期时间；静默刷新成功后会清除旧刷新错误。

## 测试结果

- Maven 全模块 `test`：132 个测试槽通过；普通运行中 8 个数据库门禁按设计跳过。
- PostgreSQL 18.4 + pgvector 真实数据库门禁：8 项全部执行并通过，Flyway V1～V17 全部成功。
- 知识源服务测试覆盖公开 HTTPS、私网/非 HTTPS 拒绝、跨域拒绝、路径边界、系统管理员权限和已有文档边界锁定。
- 动态项目数据库门禁覆盖周期、别名、动态仓库领取、更新、启停和依赖删除保护。
- Web ESLint：通过。
- Web Vitest：8 个测试文件、21 项测试通过，包含旧静默刷新错误清理回归。
- Web 生产构建：通过。
- Server、Worker 可执行 JAR 打包：通过。
- 修复后的 Server JAR 在 Flyway V17 本地数据库上真实启动，`/actuator/health` 返回 `UP`。

## 部署异常与恢复

首次 Deploy Run `32335670100` 成功完成密钥校验、SSH 配置、三张镜像拉取和 Flyway V17 迁移，但 Server 健康检查失败。使用同一生产 JAR 本地复现后确认：`AdminKnowledgeSourceService` 同时包含生产构造器和测试时钟构造器，生产构造器缺少 `@Autowired`，Spring 7 因此尝试无参实例化。

提交 `d108808` 为生产构造器补齐显式注入标记。全量 Maven 测试、打包和真实 JAR 启动通过后，CI Run `32336167376` 的 backend、frontend、server、worker、web 五个 Job 全部成功；Deploy Run `32336407366` 随后部署成功。公网 `/api/v1/system/status` 返回 `UP`，`deepseek-v4-flash` 为 ready。

## 生产页面验收

- 项目管理页成功读取 Spring AI、LangChain4j、Dify，三项均为已启用、`SUCCEEDED`、活动任务 0。
- 三项项目均显示 6 小时采集周期；聊天别名分别为 `spring ai / spring-ai`、`langchain4j` 和 `dify`。
- 知识库管理页显示 602 篇当前文档、606 个历史版本、6,134 个当前切片。
- `bge-m3` 1024 维 Embedding 完成 6,134，待处理、运行中、等待重试和失败均为 0。
- 生产 RAG 评测仍为 `PASSED`，Recall@10、MRR 和拒答准确率均为 100%。
- Spring AI 为 `200 / 3,590`，最近任务显示访问 201、发现 552、有效页面 200、最后 URL、最终心跳和连续失败 0。
- LangChain4j 为 `202 / 1,088`，Dify 为 `200 / 1,456`，两项连续失败均为 0。
- 三项来源均显示 24 小时采集周期和各自路径边界；已有数据来源的项目、根 URL 和路径输入正确锁定，名称、类型、发现 URL 和周期仍可编辑。
- 已有数据来源的删除按钮禁用；本轮未创建、保存、停用、删除或触发正式采集任务。
- 浏览器控制台未发现 warning 或 error。

## 后续边界

P1.5-B 关闭官方知识源 CRUD、项目/来源采集周期和动态 Release 聊天路由。Issue、PR、Security Advisory、RSS、用户上传资料、用户级关注关键词、引用纠错和站外通知继续进入后续阶段。
