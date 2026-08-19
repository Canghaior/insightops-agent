# P1.5-A 可配置 GitHub 项目生产验收（2026-08-20）

## 结论

P1.5-A GitHub Release 项目管理已完成开发、全量测试、CI 镜像构建、GitHub Actions 生产部署和生产只读验收。

- 生产地址：<https://insightops.canghaior.com/admin/projects>
- 功能提交：`f842482a4c131eac52cdc4de2e9b046b3e7bc781`
- CI 修复提交：`fe127c250fb5333dddc5b0682c237a85d11531f4`
- 成功 CI Run：`32274972772`
- 成功生产部署 Run：`32275352256`

## 已交付能力

- 新增 `/api/v1/admin/projects` 管理接口，覆盖列表、创建、更新、启停和删除。
- 仅 Workspace Owner 或系统管理员可以管理项目，所有查询和变更均限制在当前 Workspace。
- GitHub owner、repository 和优先级由服务端校验；Canonical URL 由服务端生成。
- 已有 Release 快照或知识数据的项目不能修改仓库坐标，避免历史证据归属被改写。
- 有业务数据、关注者或活动任务的项目不能删除；删除无业务数据项目时仅清理其终态任务历史。
- 删除流程先锁定项目行，再检查依赖并删除，避免与 Worker 领取任务发生并发竞态。
- Worker 使用数据库中的真实 owner/repository 调用 GitHub Releases API，动态项目不再依赖三个 P0 固定项目目录。
- 启用项目会自动进入增量采集计划；个人关注只控制用户更新流，不再决定全局采集是否运行。
- 前端新增“项目管理”导航和管理页，展示采集状态、Release 快照、知识源、关注人数、活动任务与计划时间。

## 测试结果

- Maven 全模块 `test`：126 项通过。
- PostgreSQL 18 + pgvector 0.8.5 真实数据库门禁：8 项通过，包含动态项目创建、领取采集、更新、停用、依赖保护和删除。
- 项目管理服务单元测试：7 项通过，覆盖权限、规范化、校验、坐标锁定、优先级更新和删除保护。
- GitHub HTTP Gateway 测试：动态非 P0 仓库 URL 路由通过。
- Release Worker 测试：数据库 owner/repository 透传通过。
- Web ESLint：通过。
- Web Vitest：8 个测试文件、20 项测试通过。
- Web 生产构建：通过。

首次 CI Run `32274309924` 正确发现终态任务历史导致空项目删除门禁断言失败。修复提交 `fe127c2` 将任务计数收窄为活动状态，并在项目行锁保护下清理终态历史；本地真实数据库门禁和后续 CI Run `32274972772` 均通过。

## 生产验收

- GitHub Actions Deploy Run `32275352256` 的密钥校验、SSH 配置、部署和健康检查步骤全部成功。
- 公网 `/api/v1/system/status` 返回 `UP`，模型 `deepseek-v4-flash` 为 ready。
- 生产管理导航已出现“项目管理”，管理页成功读取三项既有项目。
- Spring AI、LangChain4j、Dify 均显示已启用、`SUCCEEDED`、活动任务 0，并展示各自 Release 快照、知识源、关注人数和下次计划时间。
- 进入 Spring AI 编辑态后，owner/repository 输入框正确锁定，优先级仍可编辑，并显示已有采集数据的保护说明。
- 三个已有数据项目的删除按钮均被禁用；浏览器控制台未发现页面错误。
- 本轮生产页面验收未创建持久化测试项目，避免为验收向正式 Workspace 引入非业务数据；创建、启停、更新、终态任务清理和删除已由真实 PostgreSQL 门禁覆盖。

## 当前边界

P1.5-A 只关闭 GitHub Release 项目 CRUD。官方文档知识源 CRUD、自定义采集频率、Issue/PR/Security Advisory/RSS、项目别名与关键词，以及聊天工具对动态项目的识别仍属于后续阶段。
