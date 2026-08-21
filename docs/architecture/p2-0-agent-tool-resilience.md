# P2.0-C Agent 工具可靠性与可观测性

日期：2026-08-22

## 结论

P2.0-C1/C2/C3 把 P2.0-B 的只读 Function Calling 循环升级为可中断、可恢复、可审计、可监控的生产执行链路。Registry 的工具超时成为硬截止；一次 Run 同时受总时间、总尝试次数和累计工具耗时约束；瞬时故障按标准错误码进行有限重试和指数退避；GitHub、知识检索和数据库事件工具分别使用上游分组熔断器。

当前所有 Agent 工具仍是只读工具，因此本阶段的幂等边界是“同一计划内相同工具和参数不重复执行”，不引入写副作用补偿。人工审批、写操作幂等键和补偿事务属于 P2.0-D。

## 执行链路

```text
Agent Run 预算
  -> Registry 权限、Schema、timeout
  -> 逻辑 Tool Call（一次 PLAN 决策）
       -> 熔断许可
       -> Attempt 1（独立虚拟线程 + 硬截止 + 取消轮询）
       -> 可重试错误：审计失败、SSE 通知、指数退避
       -> Attempt 2..N（受 Run 总尝试和截止时间约束）
  -> SUCCEEDED / FAILED / TIMED_OUT / CANCELLED
  -> Observation 或终止 Run
```

逻辑 Tool Call 只写一次 `tool_call`；每次真实上游执行写一条 `tool_call_attempt`。用户取消或 SSE 连接失活时，等待循环会取消 Future 并以 `TOOL_CANCELLED` 结束，迟到结果不能把终态覆盖回成功。

## P2.0-C1：超时、取消与资源预算

- 单工具硬截止取 Registry timeout、Run 剩余时间和 Run 剩余工具耗时三者最小值。
- 工具在虚拟线程中执行，主执行线程按 `AGENT_TOOL_POLL_INTERVAL_MS` 检查截止时间和连接状态。
- 超时调用 `Future.cancel(true)`，尝试和逻辑调用分别写入 `TIMED_OUT`。
- 断连或用户停止生成写入 `CANCELLED`；Planner 不再接受迟到 Observation。
- Run 默认最多 90 秒、8 次真实工具尝试、60 秒累计工具执行时间。

## P2.0-C2：重试、退避与熔断

默认最多 3 次尝试，退避从 250 ms 开始并按 2 倍增长，上限 2 秒。只有下列标准错误码可重试：

- `TOOL_RATE_LIMITED`
- `TOOL_TRANSIENT_REMOTE`
- `TOOL_TIMEOUT`
- `EMBEDDING_UNAVAILABLE`
- `RETRIEVAL_ERROR`
- `EVENT_RETRIEVAL_ERROR`

权限、Schema、输入、项目范围、重复调用和输出校验错误均不重试。多次尝试仍失败时，逻辑调用以 `TOOL_RETRY_EXHAUSTED` 安全降级；原始错误保留在尝试审计中。

熔断器按 `github`、`knowledge`、`database` 三个上游组隔离。默认滑动窗口 10 次、至少 5 次调用、失败率 50% 开路 30 秒，之后允许 2 个半开探测；永久性业务错误不计入熔断失败率。

## P2.0-C3：审计、实时状态与监控

Flyway V25 新增 `tool_call_attempt`，记录尝试序号、状态、错误码、可重试标记、退避时间、耗时和起止时间。Run 详情 API 返回每个 Tool Call 的尝试列表，执行记录页面展示尝试时间线。

聊天 SSE 新增 `tool_retrying`，仅暴露工具名、轮次、下一次尝试、等待时间和标准错误码，不暴露参数、异常文本、凭据或上游响应体。

生产指标包括：

- `insightops_agent_tool_calls_total`
- `insightops_agent_tool_attempts_total`
- `insightops_agent_tool_retries_total`
- `insightops_agent_tool_timeouts_total`
- `insightops_agent_tool_duration_seconds`
- `insightops_agent_tool_circuit_rejections_total`
- `insightops_agent_tool_circuit_state`

Grafana 展示工具结果、P95 延迟、重试/超时和熔断状态；Prometheus 对高重试率、超时突增和持续开路告警。

## 生产参数

| 环境变量 | 默认值 | 作用 |
|---|---:|---|
| `AGENT_TOOL_RESILIENCE_ENABLED` | `true` | 启用重试与熔断 |
| `AGENT_TOOL_MAX_ATTEMPTS` | `3` | 单逻辑调用最多尝试次数，代码硬上限 5 |
| `AGENT_RUN_MAX_TOOL_ATTEMPTS` | `8` | 单 Run 真实工具尝试总数 |
| `AGENT_RUN_TIMEOUT_SECONDS` | `90` | Agent Run 总时间预算 |
| `AGENT_RUN_MAX_TOOL_DURATION_SECONDS` | `60` | 累计工具耗时预算 |
| `AGENT_TOOL_RETRY_INITIAL_DELAY_MS` | `250` | 首次退避 |
| `AGENT_TOOL_RETRY_MAX_DELAY_MS` | `2000` | 最大退避 |
| `AGENT_TOOL_POLL_INTERVAL_MS` | `100` | 超时/取消轮询周期 |
| `AGENT_TOOL_CIRCUIT_WINDOW_SIZE` | `10` | 熔断滑动窗口 |
| `AGENT_TOOL_CIRCUIT_MINIMUM_CALLS` | `5` | 熔断最小样本数 |
| `AGENT_TOOL_CIRCUIT_FAILURE_RATE_PERCENT` | `50` | 开路失败率阈值 |
| `AGENT_TOOL_CIRCUIT_OPEN_SECONDS` | `30` | 开路持续时间 |
| `AGENT_TOOL_CIRCUIT_HALF_OPEN_PERMITS` | `2` | 半开探测数 |

## 安全与剩余边界

- 重试仍经过原 Registry、访问级别和 Schema 校验；模型不能调整可靠性参数。
- 工具参数只存入原有受权限保护的执行审计，SSE 和指标标签不包含参数或用户数据。
- 熔断按上游组隔离，知识检索故障不会阻断数据库事件查询。
- 当前工具均为只读，重试不会产生外部写副作用。
- P2.0-D 再引入写工具审批、补偿、MCP 动态注册和更细粒度 Token/成本预算。
