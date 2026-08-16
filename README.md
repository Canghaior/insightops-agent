# InsightOps Agent

面向需要持续跟踪 AI 开源项目的 Java 开发者、架构师和技术负责人的开源情报 Agent。

当前处于 Alpha/P0：固定跟踪 Spring AI、LangChain4j 和 Dify，并已接通 GitHub Releases 第一条真实数据链路。

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

2. 在 `.env` 中填写本机配置后，一键启动数据库、后端和前端：

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

2. 构建并启动后端。不要在 Maven 聚合根项目执行 `spring-boot:run`：

   ```powershell
   mvn -pl insightops-server -am package -DskipTests
   java -jar .\insightops-server\target\insightops-server-0.1.0-SNAPSHOT.jar
   ```

3. 新开一个终端启动前端：

   ```powershell
   Set-Location insightops-web
   npm ci
   npm run dev
   ```

访问 `http://127.0.0.1:15173`。后端健康检查为 `http://127.0.0.1:18080/actuator/health`。本地 Alpha 默认将前端、后端和 PostgreSQL 都绑定到 `127.0.0.1`，避免无登录阶段被局域网直接访问。P0 Worker 目前仅提供工程与健康检查骨架，不参与按需 GitHub Release 问答，所以默认启动脚本不运行 Worker。

研究问答页面已接通 DeepSeek SSE：回答会增量显示，用户可以停止生成，完成后显示模型、Token、首 Token 时间、总耗时、RunId 和 TraceId。同一浏览器会话会复用最近 12 条用户/助手消息，支持“这个版本”等指代型追问；页面按时间连续展示问答，刷新后从 PostgreSQL 恢复最近 100 条消息。会话、用户消息、成功的 AI 消息以及成功/取消/失败 Run 会保存到 PostgreSQL。`/runs` 页面支持分页和状态筛选，Run 详情展示 Step、Tool Call、请求/结果 JSON、来源、失败原因和最终回答。

成功 Run 会按带生效日期的 DeepSeek 单价快照和可配置美元兑人民币规划汇率保存估算费用。该数值用于 Alpha 预算观察，不作为供应商账单。

流式接口：

```text
POST /api/v1/chat/streams
POST /api/v1/chat/streams/{runId}/cancel
GET  /api/v1/chat/sessions/{sessionId}/messages?limit=100
GET  /api/v1/runs?page=0&size=20&status=SUCCEEDED
GET  /api/v1/runs/{runId}
```

首次请求不传 `sessionId`，服务端创建会话并在 `started` 事件返回该 ID；后续请求传回该 `sessionId` 即可继续同一会话。前端把 ID 保存在当前浏览器标签页的 `sessionStorage` 中；“新建会话”会清除页面状态但不删除数据库历史。账号级、跨设备和可管理的长期记忆不在 P0 范围内。

当问题明确涉及三个白名单项目的 Release、版本、发布、升级或近期变化时，Agent 会执行只读工具 `github_release_list`：从 GitHub 官方 REST API 获取证据，保存 Agent Step 与 Tool Call，再由 DeepSeek 基于证据生成带官方链接的回答。P0 不允许模型指定任意仓库，也不查询 Issue、PR、Roadmap 或官方文档。

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

完整 P0 数据库门禁需要本地 PostgreSQL，使用随机隔离 Schema，结束后自动删除：

```powershell
$env:INSIGHTOPS_CHAIN_GATE='true'
.\mvnw.cmd verify
```

GitHub Actions 的 backend job 已自动启动 PostgreSQL 并执行该门禁；模型和 GitHub 均使用固定假数据，不消耗在线额度。

产品范围见 `docs/product/target-user.md`；官方来源登记见 `docs/product/tracked-projects.yaml` 和 `docs/product/official-sources.md`；架构决策见 `docs/architecture/`。
