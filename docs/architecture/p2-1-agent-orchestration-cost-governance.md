# P2.1-A/B/C 多工具编排、恢复与成本治理

## 目标

P2.1 把 P2.0 的顺序 Function Calling 循环升级为有边界、可恢复、可计量的任务图执行器：A 提供分层 DAG、受限并行和 Run 预算；B 提供显式条件边、失败分支、计划修订与跨 Run 检查点；C 提供 Workspace 日/月配额、并发预占、实际结算和管理员策略页面。

这仍不是允许模型执行任意代码的工作流平台。业务工具必须来自 Registry，参数继续经过 Schema、权限和风险校验，写工具必须独占执行并沿用人工审批、幂等 Effect 和补偿。

## P2.1-A：分层 DAG 与 Run 预算

1. Run 开始时创建 `agent_plan` 与 `agent_run_budget`。
2. 普通同轮 Function Call 形成一个候选层；独立只读节点使用 Java 21 虚拟线程并受并行度限制。
3. 写工具或待审批工具必须独占层；重复工具签名在同一 Run 中跳过。
4. 节点状态、依赖和预算持续写库并通过 SSE 发布。
5. 节点、工具尝试、规划 Token、规划估算成本任一耗尽时停止新增节点，并基于已有证据安全降级。

默认 Run 上限由 `insightops.agent.orchestration` 配置：12 个节点、并行度 3、16000 规划 Token、0.500000 元规划成本；工具尝试、总耗时和累计工具耗时继续复用 P2.0-C。

## P2.1-B：条件图、失败分支与恢复

Planner 可调用内部编排函数 `submit_task_graph` 提交显式节点、依赖、必需性和条件。服务端在执行前完成工具 Registry/权限校验、ID 与依赖校验、环检测，以及“写节点必须独占拓扑波次”校验。

支持条件：

- `ALWAYS`：依赖终态后执行；
- `ALL_SUCCESS`、`ANY_SUCCESS`、`ANY_FAILED`；
- `ERROR_CODE_MATCH`：依赖节点出现指定标准错误码时执行失败分支；
- `ALL_TERMINAL`：所有依赖进入终态后执行。

不匹配的可选节点进入 `BLOCKED/SKIPPED`，必需节点失败会写入聚合 Observation，Planner 可提交下一版计划。每次显式图保存 `agent_plan_revision`，节点保存 revision、condition 和标准错误码条件。

每个图波次或普通层结束后创建持久化安全点，内容包括证据、官方来源、结构化引用、已执行签名和预算快照。用户从 Run 详情请求暂停后，Agent 在下一安全点把 Plan/Run 标记为 `PAUSED` 并通过 `plan_paused` SSE 返回检查点 ID；用户可从聊天页携带 `resumeCheckpointId` 启动新 Run。检查点只能由同 Workspace、同原用户读取，且用原子状态更新保证只消费一次。

当前节点参数是提交图时的静态 JSON；依赖结果的动态参数由下一轮 Planner 修订计划生成，不执行任意表达式。

## P2.1-C：Workspace 配额与成本账本

`insightops.agent.cost-governance` 提供默认策略，Owner 或系统管理员可在 `/admin/agent-cost` 调整：

- 开关、每日/月度 Token 上限；
- 每日/月度人民币成本上限；
- 最大并发 Run；
- 预警百分比和硬限制开关。

Run 执行前在事务中锁定 Workspace 策略并创建唯一预占。判断同时计算已结算用量和仍有效预占，因此并发请求不能共同穿透同一配额。成功后按模型实际 Token 和版本化价格结算；失败、取消、超时或客户端断连会结算已发生用量或释放预占。预占、结算、释放、拒绝均写入幂等审计流水，日/月聚合用于管理页面。

Prometheus 指标记录预占允许/拒绝原因和结算/释放结果。管理页面展示今日、本月 Token/成本、活跃预占、策略版本和最近流水。

## 数据模型

- V27：`agent_plan`、`agent_plan_node`、`agent_plan_dependency`、`agent_run_budget`；
- V28：条件字段、`agent_plan_revision`、`agent_plan_checkpoint`、`PAUSED` Run/Plan 状态；
- V29：`agent_cost_policy`、`agent_cost_reservation`、`agent_cost_ledger`、日/月用量聚合。

原有 `agent_step`、`tool_call` 和 `tool_call_attempt` 继续保存 PLAN、TOOL、OBSERVATION 与尝试级审计；任务图和成本表提供稳定的编排、恢复与治理视图，不替代原审计链。

## 安全与当前边界

- 模型不能指定线程、绕过预算、构造未注册工具或读取其他用户检查点。
- 任务图、SSE、检查点和流水不保存密钥、内部地址或异常堆栈。
- 当前没有可视化工作流编辑器、跨服务分布式执行器、套餐订阅、支付、退款或发票。
- Workspace 技术配额已经具备；商业计费、用户级套餐和财务对账仍属于后续公开 SaaS 阶段。