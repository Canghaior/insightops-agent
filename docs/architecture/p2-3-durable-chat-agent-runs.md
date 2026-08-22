# P2.3-B：普通聊天 Agent Run 跨实例持久执行与安全点恢复

## 1. 目标

P2.3-A 已让离线评测脱离单个 Server 进程。P2.3-B 将同一套可靠性边界扩展到普通聊天：HTTP/SSE 连接只负责提交任务和观察事件，AgentLoop、工具调用与最终模型生成由 PostgreSQL 持久工作单元驱动。

目标是：

- 浏览器刷新、切换页面或 SSE 临时断开时，后台 Run 继续执行。
- 任意 Server 实例都能按事件游标恢复同一 Run 的输出。
- 当前执行实例退出后，其他实例在租约过期后接管。
- 接管优先从同一 Run 最近安全点恢复证据、引用、工具签名和预算。
- 旧 Worker 即使晚到，也不能追加事件、写终态或释放新 Worker 的成本预留。
- “断开观察”与“用户明确停止”分离；只有停止按钮/API 才请求取消。

## 2. 数据模型

Flyway V34 新增两张表：

- `agent_run_work`：保存 Workspace、原用户、会话、Prompt、访问级别、队列状态、领取次数、Worker、心跳、租约、fencing token、恢复检查点和取消请求。
- `agent_run_event`：按 Run 保存单调递增的 SSE 事件序列，支持跨实例回放。

工作状态为 `QUEUED -> RUNNING -> SUCCEEDED/FAILED/CANCELLED/PAUSED`。终态事件与 `agent_run` 用户可见结果在同一事务提交。

## 3. 领取、心跳与 fencing

`DurableChatRunQueueWorker` 按本实例剩余容量领取 `QUEUED` 或租约已过期的 `RUNNING` 工作：

1. `FOR UPDATE SKIP LOCKED` 防止多实例重复领取。
2. 每次领取增加 `attempt_count`，生成新的 `lease_token`。
3. 独立调度器定期更新 `heartbeat_at` 和 `lease_expires_at`。
4. 事件追加和终态提交都要求 Run 仍为 `RUNNING`、token 一致且租约有效。
5. 达到最大尝试次数后由最后一次领取明确写入 `AGENT_RUN_ATTEMPTS_EXHAUSTED`，避免永久悬挂。

成本结算同样服从 fencing：成功终态提交后才结算，失败/取消终态提交成功后才释放；过期 Worker 不得改变成本预留。

## 4. 接管与安全点恢复

第二个 Worker 接管时先执行恢复准备：

- 旧的 `ACTIVE/PAUSE_REQUESTED` Plan 标记为 `SUPERSEDED`。
- 未完成节点标记为 `CANCELLED/AGENT_RUN_WORKER_LOST`。
- 孤儿 `tool_call` 和 `agent_step` 标记为失败。
- 读取同一 Run 最近的 `AVAILABLE` 检查点。
- 恢复证据、官方来源、结构化引用、已执行工具签名和预算快照。
- 新 Plan 关联恢复检查点，并要求 Planner 不重复已完成调用。

跨实例接管不消费检查点；检查点仍属于原 Run。用户主动从暂停 Run 创建新 Run 的一次性消费语义保持不变。

## 5. 持久 SSE 与客户端行为

- `POST /api/v1/chat/streams` 创建 `agent_run`、持久工作单元和首个 `started` 事件，然后返回数据库事件流。
- `GET /api/v1/chat/streams/{runId}?afterSequence=N` 从序号 `N` 之后继续回放；权限仍限定原 Workspace 和原用户。
- 浏览器记录最后事件序号；流在终态前结束时自动连接恢复端点，不重复已消费事件。
- `run_recovered` 告知页面发生过 Worker 接管。
- 页面卸载只中止本地订阅，不取消 Run。
- `POST /api/v1/chat/streams/{runId}/cancel` 才写入持久取消请求；Worker 在心跳或事件边界安全终止。

## 6. 配置与指标

| 环境变量 | 默认值 | 作用 |
|---|---:|---|
| `AGENT_CHAT_QUEUE_ENABLED` | `true` | 启用普通聊天持久队列 |
| `AGENT_CHAT_QUEUE_CONCURRENCY` | `2` | 单实例并发 Run 数，限制 1～16 |
| `AGENT_CHAT_QUEUE_POLL_INTERVAL_MS` | `500` | 队列轮询间隔 |
| `AGENT_CHAT_QUEUE_INITIAL_DELAY_MS` | `1000` | 启动后首次轮询延迟 |
| `AGENT_CHAT_QUEUE_LEASE_SECONDS` | `120` | 租约时长，最小 30 秒 |
| `AGENT_CHAT_QUEUE_HEARTBEAT_SECONDS` | `15` | 心跳间隔，不超过租约三分之一 |
| `AGENT_CHAT_QUEUE_MAX_ATTEMPTS` | `3` | 最大领取次数 |
| `AGENT_CHAT_QUEUE_EVENT_POLL_MS` | `200` | SSE 持久事件轮询间隔 |

Micrometer 指标覆盖领取、接管、检查点恢复、租约丢失、取消和分发错误。

## 7. 安全边界与后续

- 工作记录固化提交时的用户、Workspace、角色和工具访问级别；接管不会扩大权限。
- 所有恢复流和取消操作都验证原 Run 所有权。
- 不引入 Redis/Kafka，复用现有 PostgreSQL 事务和备份链路。
- `OrcaTerm/` 不是发布产物，不读取、不删除、不提交其中用户文件。
- P2.3-C 继续补故障注入、队列积压/租约 SLO、告警规则和真实生产重启接管演练。
