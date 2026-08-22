# P2.3-C：普通聊天故障注入、队列 SLO 与告警闭环

## 1. 目标

P2.3-B 已保证普通聊天 Run 脱离浏览器和单个 Server 进程持久执行。P2.3-C 将该恢复能力变成可度量、可告警、可重复验收的生产可靠性闭环：

- 定时采样 PostgreSQL 中的排队、运行、过期租约和年龄状态。
- 记录接管延迟、恢复成功、租约丢失、执行器拒绝与 SSE 重连/回放。
- Prometheus 对积压、陈旧任务、过期租约和恢复停滞做分级告警。
- Grafana 同屏观察队列、恢复和客户端续流。
- 强确认演练脚本验证进程强制终止后的租约接管、安全点恢复、旧 token fencing 和单次成本结算。

## 2. SLO 快照

`DurableChatRunSloSampler` 默认每 15 秒调用一次单条聚合 SQL。查询只读取 `agent_run_work`，输出：

| 指标 | 含义 |
|---|---|
| `insightops_agent_chat_queue_queued` | 等待领取的 Run 数 |
| `insightops_agent_chat_queue_running` | 当前持有或等待接管的 Run 数 |
| `insightops_agent_chat_queue_expired_leases` | 已过期但尚未接管的运行租约 |
| `insightops_agent_chat_queue_oldest_queued_age_seconds` | 最老排队 Run 的等待秒数 |
| `insightops_agent_chat_queue_oldest_heartbeat_age_seconds` | 最老运行心跳距今秒数 |
| `insightops_agent_chat_queue_reclaim_delay_seconds` | 租约过期到新 Worker 领取的延迟 Timer |

采样异常只增加 `snapshot_errors` 并记录告警日志，不终止调度线程。采样间隔由 `AGENT_CHAT_QUEUE_SNAPSHOT_INTERVAL_MS` 配置，默认 15000 ms。

## 3. 客户端续流指标

持久 SSE 连接增加以下计数：

- `connections`：打开观察连接。
- `reconnects`：`afterSequence > 0` 的恢复连接。
- `replayed_events`：从 PostgreSQL 回放给客户端的事件数。
- `disconnects`：IO、Emitter 状态或线程中断导致的非终态断开。

这些指标不把浏览器断开误判为 Agent Run 失败，Run 的真实状态仍以持久工作单元为准。

## 4. 告警策略

P2.3-C 增加八条规则：排队超过 20、最老等待超过 30 秒、存在过期租约、接管后五分钟仍未恢复、接管延迟 p95 超过 30 秒、租约丢失突发、分发错误突发和 SLO 采样失败。严重度按“影响恢复正确性”为 critical、“容量或趋势异常”为 warning。

规则使用持续窗口，避免单次采样或正常短暂接管造成抖动。Grafana 增加四个面板，并保留原 HTTP、JVM、数据库和工具可靠性视图。

## 5. 故障注入边界

`scripts/p2-3-chat-takeover-drill.sh` 只接受一个正在运行且已有 `AVAILABLE` 安全点的 Run UUID，并强制要求 `--confirm-production-restart`。脚本在任何破坏动作之前确认：

1. 路径确实是 `/opt/insightops-agent`，生产预检通过。
2. Run 状态为 `RUNNING` 且当前有 Worker。
3. 已存在安全点。
4. 成本账本只有一条 `RESERVE`，没有提前出现 `SETTLE/RELEASE`。

随后脚本只向 Server 容器发送一次 `SIGKILL` 并立即拉起，不停止 PostgreSQL、Worker、Caddy，也不删除卷。验收必须同时满足：

- Server 重新健康。
- 租约过期后 `attempt_count` 增加且 Worker 身份改变。
- 新增 `run_recovered` 事件。
- 接管前旧 Worker 没有写入终态成本记录。
- Run 最终进入 `SUCCEEDED/FAILED/CANCELLED`。
- 成本预留最终只有一个 `SETTLE` 或 `RELEASE`，状态一致。

## 6. 非目标

- 不把生产故障演练接入无人值守 CI。
- 不自动生成测试 Run，避免演练脚本持有用户会话或绕过权限。
- 不删除或修改业务数据、备份、上传卷或 `OrcaTerm/`。
- 不以指标替代 PostgreSQL fencing；指标只观察，不参与正确性判定。
