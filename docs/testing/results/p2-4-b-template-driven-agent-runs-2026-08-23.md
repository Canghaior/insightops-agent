# P2.4-B 模板驱动 Agent Run 验收记录

日期：2026-08-23
状态：本地全链路通过，生产发布验收待补记

## 1. 验收范围

- 从 Workspace 当前活动模板版本创建真实持久 Agent Run。
- 创建时固化模板 ID、版本 ID、图规范、入口参数、入口问题和工具合同指纹。
- 支持 `${inputs.*}` 与 `${node.output.*}`，并限制为显式依赖和 `exposeOutputs` 白名单。
- 节点状态、尝试、解析输入、输出、公开输出、耗时、Token、费用、错误、Tool Call 和 Plan Node 可追踪。
- 复用 Durable Chat Run 的队列、心跳租约、旧 Worker fencing、安全点、超时和成本结算。
- 失败后从真实失败节点创建新 Run，成功节点以 `REUSED` 复用且不重复执行。
- 写工具继续经过审批、Effect 幂等和补偿边界；待审批或取消不会继续执行下游。
- 前端完成活动模板启动、动态入口参数、运行 DAG 详情、静默刷新和失败节点重试闭环。

## 2. 关键实现

### 2.1 数据与恢复

Flyway V36 新增 `agent_workflow_run`、`agent_workflow_run_node` 和 `agent_workflow_node_attempt`。所有节点开始与完成写入都校验 `agent_run_work` 当前 lease token；接管时旧实例尝试结算为 `FAILED / RUN_RECOVERED`，新实例以递增 attempt 继续。每个完成波次同时写入现有 Agent 安全点。

### 2.2 表达式与合同

入口参数支持 string、integer、boolean、string array、JSON object 和 JSON array。完整值表达式保留原类型，字符串插值仅接受标量。模板保存/激活与运行时分别验证引用、依赖、公开字段、工具访问级别和工具输入合同。工具合同漂移时以 `WORKFLOW_TOOL_CONTRACT_CHANGED` 安全失败。

### 2.3 API 与 UI

- `GET /api/v1/agent-workflows`
- `POST /api/v1/agent-workflows/{templateId}/runs`
- `GET /api/v1/agent-workflows/runs/{runId}`
- `POST /api/v1/agent-workflows/runs/{runId}/retries`
- 页面：`/agent-workflows`、`/runs/{runId}`

所有 API 均沿用现有认证；模板按 Workspace 隔离，Run 详情和重试额外校验 owner。

## 3. 自动化结果

### 3.1 后端与真实 PostgreSQL

命令：

```powershell
$env:INSIGHTOPS_CHAIN_GATE='true'
$env:DB_URL='jdbc:postgresql://localhost:55432/insightops'
$env:DB_USERNAME='insightops'
$env:DB_PASSWORD='insightops_dev'
.\mvnw.cmd verify
```

结果：

- PostgreSQL：18.4（pgvector 0.8.5）。
- Flyway：36/36。
- Tests：269/269。
- Failures：0。
- Errors：0。
- Skipped：0。
- Reactor：Core、Infrastructure、Server、Worker 全部 SUCCESS。

P2.4-B 专项数据库门禁验证：

- 模板/版本/图/输入快照可完整读取。
- 节点 attempt `1 → 2`。
- 旧 lease 写回被 `AGENT_RUN_LEASE_LOST` 拒绝。
- 旧运行中尝试记录为 `RUN_RECOVERED`。
- 新 lease 成功提交输出和公开字段。

### 3.2 前端

```powershell
npm run lint
npm run test -- --run
npm run build
```

结果：

- ESLint：通过，0 warning。
- Vitest：22 files、56/56。
- `vue-tsc -b`：通过。
- Vite 8.2.1 生产构建：通过。

## 4. 生产发布验收

待代码提交、CI、部署和生产 Smoke 完成后补记提交、GitHub Actions Run、生产 Flyway、页面/API 状态和真实模板 Run。
