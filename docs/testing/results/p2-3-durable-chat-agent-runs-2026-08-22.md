# P2.3-B 普通聊天持久执行与安全点恢复验收记录（2026-08-22）

## 1. 当前结论

P2.3-B 已完成实现、全量自动化门禁、提交、CI、不可变镜像部署和生产健康验收，当前状态为“生产可用”。

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

## 4. 生产验收

- 生产提交：`b2b5df3d2fa239e2f2697461ec9bd8fe8f4253f4`。
- GitHub Actions CI：Run `32577494585`，后端、前端、Server/Worker/Web 三份镜像全部成功。
- GitHub Actions 生产部署：Run `32577695797`，成功。
- 部署脚本完成生产预检、一致性备份、不可变 SHA 镜像拉取，以及 PostgreSQL、Ollama、Server、Worker、Web、Caddy 六服务健康门禁；包含 V34 的 Server 成功通过启动和数据库健康检查。
- 公网首页返回 HTTP 200，新 Web 资产为 `index-BJYW0MZd.js`；未登录访问 Server 管理端点返回 HTTP 401，认证边界正常。

P2.3-B 的生产发布闭环已关闭。运行中强制杀进程、等待租约过期、观察 `run_recovered` 和核对成本账本的真实故障注入属于 P2.3-C 可靠性演练；当前实现已由真实 PostgreSQL 接管/fencing 门禁覆盖，不以绕过终端或浏览器安全边界的方式执行生产破坏性试验。
