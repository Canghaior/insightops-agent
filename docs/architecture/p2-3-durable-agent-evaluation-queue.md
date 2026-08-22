# P2.3-A：Agent 评测持久队列、租约与断点接管

## 1. 目标

P2.2 的评测请求虽然异步执行，但任务提交到 Server 进程内线程池；进程重启、线程池拒绝或实例退出时，数据库中的 `QUEUED/RUNNING` Run 缺少可靠领取、心跳和接管协议。P2.3-A 将评测执行升级为数据库持久队列，目标是：

- HTTP 请求只负责创建 `QUEUED` Run，不持有执行生命周期。
- 一个 Run 同一时刻只能由一个持有有效租约的 Worker 写入。
- Server 重启后，未领取任务仍保留，过期运行任务可由其他实例接管。
- 已完成案例不重复调用模型；只重跑缺失案例。
- 旧 Worker 晚到时不能覆盖新 Worker 的结果或终态。
- 孤儿 Agent Run 被明确终止，成本预占尝试安全释放。

本阶段只改造 Agent 评测执行，不把聊天 Agent Run 迁移为跨服务分布式任务，也不引入外部消息中间件。

## 2. 数据模型

Flyway V33 为 `agent_evaluation_run` 增加：

- `attempt_count`：成功领取次数。
- `claimed_by`：当前 Server Worker 标识。
- `lease_token`：每次领取生成的新 fencing token。
- `heartbeat_at`：最近成功续租时间。
- `lease_expires_at`：当前租约失效时间。

同时为 `agent_run` 增加 `evaluation_run_id` 和 `evaluation_case_id`，使孤儿执行、案例重放和成本清理可以精确关联。

## 3. 领取协议

`AgentEvaluationQueueWorker` 按配置容量轮询：

1. 在事务中查询 `QUEUED` 或租约已过期的 `RUNNING` Run。
2. 使用 `FOR UPDATE SKIP LOCKED` 避免多实例重复领取。
3. 原子增加 `attempt_count`，写入 `claimed_by`、新 `lease_token`、心跳和租约到期时间。
4. 在线程池有容量时分发执行；线程池拒绝时立即恢复本机 `inFlight`，任务等待租约到期后重新领取。
5. 达到最大尝试次数的过期 Run 终态化为 `EVALUATION_ATTEMPTS_EXHAUSTED`。

## 4. 心跳与 fencing

执行线程使用独立的定时执行器续租。所有关键写入同时校验：

- Run 仍为 `RUNNING`；
- `lease_token` 与当前领取一致；
- 租约尚未到期。

受保护的写入包括 Agent Run 创建/完成/失败、案例结果保存、评测完成/失败和主动续租。租约到期或 token 被新领取替换后，旧 Worker 的写入返回 `false`，服务将其识别为 `EVALUATION_LEASE_LOST`，停止继续提交终态。

## 5. 接管与断点续跑

接管开始时，服务执行以下恢复动作：

- 找出当前评测下仍为 `RUNNING` 的孤儿 Agent Run，标记为 `EVALUATION_WORKER_LOST`。
- 对孤儿 Run 调用幂等成本释放；成本账本异常不会阻断新 Worker 接管。
- 读取已有 `agent_evaluation_case_result`，直接纳入最终汇总。
- 只为没有持久结果的案例创建新的 Agent Run。
- 在最终汇总前再次续租，只有有效 token 能写评测终态和候选状态。

## 6. 配置与可观测性

环境变量：

| 变量 | 默认值 | 作用 |
|---|---:|---|
| `AGENT_EVALUATION_QUEUE_ENABLED` | `true` | 是否启用评测队列 Worker |
| `AGENT_EVALUATION_QUEUE_CONCURRENCY` | `1` | 单实例最大并发评测数，限制为 1～8 |
| `AGENT_EVALUATION_QUEUE_POLL_INTERVAL_MS` | `2000` | 轮询间隔 |
| `AGENT_EVALUATION_QUEUE_INITIAL_DELAY_MS` | `1000` | 启动后首次轮询延迟 |
| `AGENT_EVALUATION_QUEUE_LEASE_SECONDS` | `180` | 租约时长，最小 30 秒 |
| `AGENT_EVALUATION_QUEUE_HEARTBEAT_SECONDS` | `30` | 心跳间隔，不超过租约的三分之一 |
| `AGENT_EVALUATION_QUEUE_MAX_ATTEMPTS` | `3` | 最大领取次数 |

新增 Micrometer 指标：

- `insightops.agent.evaluation.claims`
- `insightops.agent.evaluation.reclaims`
- `insightops.agent.evaluation.lease.lost`
- `insightops.agent.evaluation.dispatch.errors`
- 原有运行中数量、Run 结果、耗时和案例错误指标继续保留。

管理页在排队/运行状态展示 Worker、尝试次数、心跳和租约到期时间，并说明服务重启后的接管行为。

## 7. 安全与边界

- `OrcaTerm/` 不属于发布产物，本阶段不读取、删除或提交其中的用户文件。
- 评测仍只向 AgentLoop 暴露只读工具；P2.0-D 的写工具审批边界不变。
- 队列使用现有 PostgreSQL，不引入 Redis、Kafka 或额外运维组件。
- P2.3-A 解决评测执行可靠性；聊天 Run 的跨实例持久执行属于后续 P2.3-B。

## 8. 后续阶段

- P2.3-B：把聊天 Agent Run/任务图执行也抽象为可领取的持久工作单元，支持实例退出后的安全点恢复。
- P2.3-C：补充故障注入、积压/租约 SLO、告警规则和生产重启接管演练，并形成运行手册。
