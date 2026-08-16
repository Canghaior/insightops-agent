# InsightOps Agent

[![CI](https://github.com/Canghaior/insightops-agent/actions/workflows/ci.yml/badge.svg)](https://github.com/Canghaior/insightops-agent/actions/workflows/ci.yml)

面向需要持续跟踪 AI 开源项目的 Java 开发者、架构师和技术负责人的开源情报 Agent。

当前处于 Alpha/P1：在 P0 GitHub Releases 真实数据链路之上，已加入登录、个人工作区隔离、账号级会话管理、长期记忆、个人项目关注、邀请制用户与权限管理，以及自动采集和个人已读状态隔离的项目更新中心。

## 工程结构

```text
insightops-core            纯 Java 领域规则与状态机
insightops-infrastructure  JPA、JdbcClient、Flyway、DeepSeek 配置
insightops-server          REST/SSE API 入口（本地端口 18080）
insightops-worker          采集和异步任务进程（本地端口 18081）
insightops-web             Vue 3 + TypeScript 工作台（本地端口 15173）
infra                      PostgreSQL 18 + pgvector Compose
docs                       产品、架构、评测与测试文档
```

依赖方向是 `server/worker -> infrastructure -> core`，`core` 不依赖 Spring、JPA 或模型 SDK。

## 本地启动

要求：Java 21、Maven 3.9+、Node 24、Docker Compose。

### 一键启动（Windows）

1. 创建本地配置：

   ```powershell
   Copy-Item .env.example .env
   ```

2. 在 `.env` 中填写本机配置。以下账号会在启动时创建或提升为首个 `SYSTEM_ADMIN + OWNER`（密码不得提交）：

   ```properties
   AUTH_BOOTSTRAP_ENABLED=true
   AUTH_BOOTSTRAP_USERNAME=alpha-owner
   AUTH_BOOTSTRAP_DISPLAY_NAME=Alpha Owner
   AUTH_BOOTSTRAP_PASSWORD=请设置10到72位且含大小写字母和数字的密码
   ```

   然后一键启动数据库、后端和前端：

   ```powershell
   .\scripts\start-dev.ps1
   ```

3. 访问 `http://127.0.0.1:15173`。停止全部本地服务：

   ```powershell
   .\scripts\stop-dev.ps1
   ```

运行日志保存在未提交的 `.runtime/`。只停止后端和前端、保留数据库：

```powershell
.\scripts\stop-dev.ps1 -KeepDatabase
```

### 手动启动

1. 启动数据库：

   ```powershell
   docker compose --env-file .env -f infra/compose.yaml up -d
   ```

2. 构建并分别启动 Server 与 Worker。不要在 Maven 聚合根项目执行 `spring-boot:run`：

   ```powershell
   mvn -pl insightops-server,insightops-worker -am package -DskipTests
   java -jar .\insightops-server\target\insightops-server-0.1.0-SNAPSHOT.jar
   ```

   再新开一个终端：

   ```powershell
   java -jar .\insightops-worker\target\insightops-worker-0.1.0-SNAPSHOT.jar
   ```

3. 再新开一个终端启动前端：

   ```powershell
   Set-Location insightops-web
   npm ci
   npm run dev
   ```

访问 `http://127.0.0.1:15173` 并使用 `.env` 中的本地账号登录。Server 和 Worker 健康检查分别为 `http://127.0.0.1:18080/actuator/health`、`http://127.0.0.1:18081/actuator/health`。前端、两个 Java 进程和 PostgreSQL 默认只绑定 `127.0.0.1`。启动脚本会同时运行 Worker；它首次启动后立即采集已关注项目，之后默认每 6 小时增量同步 GitHub Releases，失败时按原因退避重试。

系统采用封闭邀请制，不提供公开注册接口。`SYSTEM_ADMIN` 可在“用户管理”中创建系统管理员、Owner 或 Member；工作区 `OWNER` 只能创建和管理普通 Member；`MEMBER` 只能使用业务功能。管理员创建用户时设置临时密码，新用户首次登录只能进入“账号设置”修改密码，完成后旧会话立即失效。普通用户和管理员使用同一套工作台，管理菜单按权限显示，不维护两套重复前端。

研究问答页面已接通 DeepSeek SSE：回答会增量显示，用户可以停止生成，完成后显示模型、Token、首 Token 时间、总耗时、RunId 和 TraceId。同一浏览器会话会复用最近 12 条用户/助手消息，支持“这个版本”等指代型追问；页面按时间连续展示问答，刷新后从 PostgreSQL 恢复最近 100 条消息。会话、用户消息、成功的 AI 消息以及成功/取消/失败 Run 会保存到 PostgreSQL。`/runs` 页面支持分页和状态筛选，Run 详情展示 Step、Tool Call、请求/结果 JSON、来源、失败原因和最终回答。

成功 Run 会按带生效日期的 DeepSeek 单价快照和可配置美元兑人民币规划汇率保存估算费用。该数值用于 Alpha 预算观察，不作为供应商账单。

流式接口：

```text
POST /api/v1/chat/streams
POST /api/v1/chat/streams/{runId}/cancel
POST /api/v1/auth/login
GET  /api/v1/auth/me
POST /api/v1/auth/logout
POST /api/v1/auth/password
GET  /api/v1/admin/users
POST /api/v1/admin/users
PATCH /api/v1/admin/users/{userId}/status
PATCH /api/v1/admin/users/{userId}/role
POST /api/v1/admin/users/{userId}/reset-password
GET  /api/v1/admin/audit?limit=100
GET  /api/v1/chat/sessions?includeArchived=true
PATCH /api/v1/chat/sessions/{sessionId}
DELETE /api/v1/chat/sessions/{sessionId}
GET  /api/v1/chat/sessions/{sessionId}/messages?limit=100
GET/POST/PUT/DELETE /api/v1/memories
GET  /api/v1/projects
PATCH /api/v1/projects/{projectId}/watch
GET  /api/v1/updates?page=0&size=20&projectId=&unreadOnly=false
GET  /api/v1/updates/unread-count
POST /api/v1/updates/{eventId}/read
POST /api/v1/updates/read-all
GET  /api/v1/admin/collection
POST /api/v1/admin/collection/{projectId}/sync
GET  /api/v1/runs?page=0&size=20&status=SUCCEEDED
GET  /api/v1/runs/{runId}
```

首次请求不传 `sessionId`，服务端创建当前登录用户的会话并在 `started` 事件返回该 ID；后续请求传回该 `sessionId` 即可继续同一会话。会话列表来自 PostgreSQL，可跨标签页和设备恢复，并支持改名、归档、恢复和删除。删除会话时保留 Agent Run 审计记录。长期记忆由用户显式新增、启停、修改和删除，只用于个性化表达，不作为版本事实证据。

当问题明确涉及三个白名单项目的 Release、版本、发布、升级或近期变化时，Agent 会执行只读工具 `github_release_list`：从 GitHub 官方 REST API 获取证据，保存 Agent Step 与 Tool Call，再由 DeepSeek 基于证据生成带官方链接的回答。P0 不允许模型指定任意仓库，也不查询 Issue、PR、Roadmap 或官方文档。

项目更新中心只展示当前工作区已关注项目的 Release。采集证据全局去重保存，已读状态按用户隔离；点击“基于本次更新研究”会把带项目和版本的研究问题预填到问答页。`SYSTEM_ADMIN` 可在用户管理页查看每个项目的采集状态、错误和下次执行时间，并请求立即同步。设计细节见 [P1 项目更新中心](docs/architecture/p1-project-update-center.md)。

P0 聊天入口已启用最小 Guardrail：统一限制输入长度和控制字符，把用户、历史与工具内容标记为不可信数据，禁止泄露系统提示词或密钥，并在模型调用前校验引用必须为 GitHub 官方 Release tag URL。前端同时使用安全 Markdown 子集渲染回答。完整边界见 [P0 聊天 Guardrail](docs/architecture/p0-chat-guardrail.md)。

## DeepSeek API Key

只在未提交的根目录 `.env` 中填写：

```properties
DEEPSEEK_API_KEY=你的Key
DEEPSEEK_ENABLED=true
SPRING_AI_MODEL_CHAT=openai
```

这里的 `openai` 表示使用 Spring AI 的 OpenAI-compatible 协议适配器，实际 Provider 和模型仍然是 DeepSeek 官方 API 与 `deepseek-v4-flash`。这样才能向 DeepSeek V4 发送 `thinking.type=disabled`。

不要把真实 Key 写进 `.env.example`、YAML、Java 源码、提交记录或聊天消息。在线 Smoke 入口默认关闭，只在显式设置 `DEEPSEEK_SMOKE_TEST_ENABLED=true` 的本地进程中出现。

## 验证

```powershell
.\mvnw.cmd test
Set-Location insightops-web
npm run lint
npm test
npm run build
```

普通自动测试不调用在线模型；真实 DeepSeek 验证结果记录在 `docs/testing/results/`。

完整数据库门禁需要本地 PostgreSQL，使用随机隔离 Schema，结束后自动删除；它同时覆盖 P0 Agent 链路、P1 账号管理与项目更新去重/已读隔离：

```powershell
$env:INSIGHTOPS_CHAIN_GATE='true'
.\mvnw.cmd verify
```

GitHub Actions 的 backend job 已自动启动 PostgreSQL 并执行该门禁；模型和 GitHub 均使用固定假数据，不消耗在线额度。

产品范围见 `docs/product/target-user.md`；官方来源登记见 `docs/product/tracked-projects.yaml` 和 `docs/product/official-sources.md`；架构决策见 `docs/architecture/`。
