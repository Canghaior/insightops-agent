# P2.3-B 普通聊天持久执行与安全点恢复验收记录（2026-08-22）

## 1. 当前结论

P2.3-B 功能实现和本地全量自动化门禁通过；尚未提交、推送或部署生产，因此当前状态为“本地完成、生产验收待关闭”。

已验证：

- 普通聊天请求进入 PostgreSQL 持久队列，HTTP/SSE 连接不再拥有执行生命周期。
- 多实例使用租约、心跳和 fencing token 领取；过期 Worker 不能追加事件或提交终态。
- 接管会清理旧 Plan、Node、Tool Call 和 Step，并恢复同一 Run 最近安全点。
- 安全点预算、证据、来源、引用和已执行签名可恢复，不重复已完成工具调用。
- 取消请求持久化；浏览器断线或页面卸载不会误取消后台 Run。
- SSE 可从最后序号跨实例续流，并展示 `run_recovered`。
- 终态和聊天结果原子提交；成本结算/释放在 fencing 成功后执行。

## 2. 后端全仓门禁

命令：

```powershell
$env:INSIGHTOPS_CHAIN_GATE='true'
mvn -q verify
```

结果：

- Surefire：243/243。
- Failures：0。
- Errors：0。
- Skipped：0。
- Flyway：34/34，包含 `V34__add_durable_chat_agent_runs.sql`。
- `P23DurableChatRunDatabaseGateTest` 在真实 PostgreSQL 18.4 隔离 Schema 验证迁移、过期接管、检查点选择、孤儿清理、取消及旧 token fencing，测试结束后自动清理 Schema。
- `DurableChatRunQueueWorkerTest` 验证 Spring 生产构造函数、容量领取、接管指标、禁用开关和线程池拒绝后的容量恢复。
- `AgentCheckpointServiceTest` 验证同 Run 恢复不消费检查点。
- `AgentRunExecutionBudgetTest` 验证接管后预算累计值继续生效。

## 3. 前端门禁

```powershell
npm run lint
npm test
npm run build
```

结果：

- ESLint：PASS。
- Vitest：20 个测试文件，48/48。
- durable SSE 专项验证：流提前结束后携带 `afterSequence` 自动续接，恢复事件和终态按序到达。
- `vue-tsc`：PASS。
- Vite production build：PASS。

## 4. 生产验收待办

- 提交并推送 P2.3-B，等待 CI 全绿。
- 部署后确认 Flyway 当前版本为 34，Server/Worker/Web 健康。
- 发起普通聊天 Run，在执行中重启当前 Server，等待租约过期并确认第二实例/重启实例接管。
- 验证页面自动续流、`run_recovered`、最终答案、引用、Run Trace、预算和成本账本一致。
- 验证用户明确停止能取消，单纯刷新/关闭页面不会取消。
