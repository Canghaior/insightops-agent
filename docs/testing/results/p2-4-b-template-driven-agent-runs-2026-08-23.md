# P2.4-B 模板驱动 Agent Run 验收记录

日期：2026-08-23 ～ 2026-08-24
状态：本地全链路、CI、生产部署、认证态真实 Run 与只读证据复核通过

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
- Tests：271/271。
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

## 4. 生产发布与认证态真实 Run 验收

### 4.1 初始发布

- 生产代码提交：`d130d49bb3b9ecebe8c504e11ab2066aea258edc`。
- CI Run：`32651837086`；Deploy Run：`32652032226`，部署作业 `97225038238`。
- V36 随 Server 启动；公网 `/`、`/agent-workflows`、`/admin/agent-workflows` 均为 HTTP 200。
- `/api/v1/system/status` 为 `UP`，DeepSeek `deepseek-v4-flash ready=true`；未登录工作流 API 为 HTTP 401。

### 4.2 生产故障发现与修复

- 首次受保护 READ_ONLY Run `0171e5b9-6938-491e-9edb-a3c9e7018764` 暴露 `executeWave()` 返回不可变列表后再次排序导致的 `UnsupportedOperationException`。修复提交 `2e28b841023f47fcbbac31ca61a3806913c391fe` 增加可变副本排序和回归测试，经 CI Run `32692451092`、Deploy Run `32692677352` 发布。
- 用户明确授权的替代 Run `c957fdd9-2425-4cdb-a2c4-f45e76eb685b` 在工作流 Run `32693122412` 中执行成功；后置校验同时发现 Jackson 2 `JsonNode` 被 Spring Boot 4.1/Jackson 3 HTTP 层展开为类型元数据。修复提交 `868c1ea402754752d30a99e74943aa4b2e153f7c` 将动态 JSON 转为标准 Map/List/标量并增加 API 回归测试，经 CI Run `32694809322`、Deploy Run `32695040331`（job `97335462074`）发布。
- 修复后公开 Smoke 再次通过：三个页面 HTTP 200、服务 `UP`、DeepSeek ready、未登录 API 401。

### 4.3 替代 Run 最终只读复核

工作流提交 `1eb76f1e0cefd208789a1ac98cdaaef23d8db0c7` 支持以创建时的原始 marker 只读核验既有 Run。验证工作流 Run `32695436333`、job `97336537076` 成功，全程未创建新业务 Run：

- 模板：`版本对比` v1，2 个 READ_ONLY 节点。
- immutable version 与 Run graph snapshot SHA-256 均为 `f47f65521ae4acfecaca92dbe64172655ddab97012400d233299b98cab8c2680`。
- 数据库节点汇总 `total|succeeded|attempts=2|2|2`。
- 安全点 `f2ca4601-0c6f-474b-ace2-8803927c0177`，状态 `AVAILABLE`，原因为 `WORKFLOW_WAVE_COMPLETED`。
- 成本汇总 `SETTLED|1|1`，即一次 RESERVE、一次 SETTLE，无重复结算。
- 模板 ID、不可变版本 ID、入口参数 marker、工具合同指纹、节点输入输出、Tool Call、Plan Node 与节点尝试均通过断言。

至此，P2.4-B 的认证态生产模板驱动真实 Run、不可变图快照、节点执行证据、安全点与单次成本结算闭环完成。
