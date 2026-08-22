# P2.3-A 持久评测队列验收记录（2026-08-22）

## 1. 验收结论

P2.3-A 已提交、部署并完成生产健康验收。

已验证：

- 评测 API 只创建 `QUEUED` Run，不在请求线程调用模型。
- Server 队列 Worker 按并发容量领取并分发任务。
- 过期租约可由第二个 Worker 接管，`attempt_count` 正确增加。
- 接管会终止关联的孤儿 Agent Run，错误码为 `EVALUATION_WORKER_LOST`。
- 旧 lease token 无法续租、完成评测或覆盖新 Worker 结果。
- 接管后复用已完成案例，不重复调用 AgentLoop。
- 线程池拒绝分发时不会泄漏本机并发容量，并记录 dispatch error。
- 管理页可显示队列、Worker、尝试次数、心跳和租约到期时间。

## 2. 后端全仓门禁

命令：

```powershell
$env:INSIGHTOPS_CHAIN_GATE='true'
mvn -q verify
```

结果：

- Surefire 测试：236/236。
- Failures：0。
- Errors：0。
- Skipped：0。
- `P0ChainDatabaseGateTest`：17/17。
- `P22AgentEvaluationDatabaseGateTest`：2/2。
- Flyway：33/33，包含 `V33__add_durable_agent_evaluation_queue.sql`。

真实 PostgreSQL 门禁使用本机 `pgvector/pgvector:0.8.5-pg18` 容器和隔离 schema，执行完成后自动清理测试 schema。

## 3. P2.3-A 专项测试

- `AgentEvaluationServiceTest`：4/4。
  - 请求只入队。
  - 真实只读评测执行与汇总。
  - 接管后复用已完成案例。
  - 工具合同和输入校验回归。
- `AgentEvaluationQueueWorkerTest`：3/3。
  - 容量内领取与分发。
  - 队列禁用不领取。
  - 线程池拒绝后容量恢复和指标记录。
- `P22AgentEvaluationDatabaseGateTest`：2/2。
  - P2.2 发布基线/激活回归。
  - 过期接管、孤儿终止、token fencing 和终态写入。

## 4. 前端门禁

```powershell
npm test -- --run
npm run lint
npm run build
```

结果：

- Vitest：20 个测试文件、46/46。
- ESLint：0 error、0 warning。
- `vue-tsc`：通过。
- Vite production build：通过。

## 5. 生产验收

- 生产提交：`b1ea13be18303b41305f492aa1f021c6c6b6bf00`。
- GitHub Actions 生产部署：Run `32572712131`，成功。
- Flyway V33、Server、Worker、Web 和生产健康检查通过。
- 首次版本 `17c2405` 因 `AgentEvaluationQueueWorker` 构造函数选择失败触发自动回滚；`b1ea13b` 显式标记生产构造函数后重新部署成功。
- P2.3-A 生产闭环已关闭；普通聊天跨实例执行由 P2.3-B 继续建设。
