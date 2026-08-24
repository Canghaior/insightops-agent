# P2.3-B 持久聊天续流生产修复验收记录（2026-08-24）

## 1. 结论

持久聊天 Run 在后台正常完成、但浏览器长期停留在骨架屏且必须刷新才显示答案的问题已修复。用户在生产环境复测复杂研究问题后确认：答案会自动出现，不再依赖手动刷新。

“你是什么模型”一类身份问题由 `ChatQuickReplyPolicy` 本地快速回答，不创建持久 Agent Run，也不调用 DeepSeek；因此该问题的毫秒级返回不能作为真实模型续流链路的验收依据。

## 2. 故障表现与根因

### 2.1 故障表现

- 普通研究问题创建持久 Run 后，页面持续显示“正在创建 Agent Run”或“正在连接 DeepSeek”。
- 后台 Run 与答案已经持久化，刷新页面后能从数据库恢复完整答案。
- 本地快速回答问题能够立即完成。

### 2.2 三层根因

1. 长连接在代理或网络异常下可能无事件、无关闭，前端原先没有首事件和空闲超时。
2. 超时分支等待 `reader.cancel()`，浏览器实现可能长期不返回；同一 SSE 端点重连也无法稳定绕过中间层连接问题。
3. 新增 JSON 事件回补端点最初直接返回 Jackson 2 `ObjectNode`；当前 Spring Boot 4.1/Jackson 3 HTTP 边界会把动态节点序列化成不兼容结构，前端解析失败后静默重试。

## 3. 修复内容

### 3.1 `666a4aca84e2a69777017f8d180cee06361f41f0`

- 服务端 SSE 增加心跳。
- 前端增加首事件超时、空闲超时和更明确的连接状态。
- 增加超时与状态转换测试。

CI Run `32703023193`、部署 Run `32703374472` 成功。

### 3.2 `f8299911d9e3fd15dfdef4b1ee851a8c66200e53`

- 超时后不再等待 `reader.cancel()` 完成。
- 新增 `GET /api/v1/chat/streams/{runId}/events?afterSequence=N` 持久事件回补接口。
- 前端在 SSE 异常后切换为 JSON 轮询，并按事件序号去重。

CI Run `32705748636`、部署 Run `32706114903` 成功。

### 3.3 `60c94ab0e0cddca08beef1c61f38446de830bb18`

- 回补响应不再跨 HTTP 边界暴露 Jackson `ObjectNode`。
- 服务端将事件转换为标准 Java Map/List/字符串/数字/布尔值后返回。
- 测试明确验证回补事件均为标准 `Map`，并保留类型、Provider 和序号。

CI Run `32708453090` 的 frontend、backend、Build worker image、Build web image、Build server image 五项全部成功；部署 Run `32708756290`、job `97375458103` 成功。

## 4. 自动化结果

| 门禁 | 结果 |
|---|---|
| 后端真实 PostgreSQL 全仓 | 273/273，Failures 0、Errors 0、Skipped 0 |
| PostgreSQL / Flyway | PostgreSQL 18.4，Flyway 36/36 |
| 前端 Vitest | 22 个测试文件，57/57 |
| 前端质量 | ESLint、vue-tsc、Vite 8.2.1 生产构建通过 |
| Git 差异检查 | `git diff --check`、暂存差异检查通过 |

## 5. 生产验收

- `/chat` 返回 HTTP 200。
- `/api/v1/system/status` 返回 `service=insightops-server`、`status=UP`。
- DeepSeek `deepseek-v4-flash` 返回 `ready=true`。
- 未登录访问事件回补接口返回 HTTP 401。
- 用户复测复杂研究问题，确认无需刷新即可自动显示答案。

## 6. 安全与阶段边界

- 事件回补接口继续使用既有认证和 Run 所有权校验。
- 回补只读取单调持久事件，不创建第二个 Run，不重复结算 Token 或费用。
- 前端按 `sequence` 去重，SSE 与 JSON 回补并存时不会重复呈现事件。
- 本次只修复 P2.3-B 观察链路，不改变 P2.4-B 模板执行、安全点、租约、审批、幂等或补偿边界。
