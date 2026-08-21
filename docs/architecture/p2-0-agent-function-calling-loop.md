# P2.0-B Function Calling 多轮 Agent

日期：2026-08-21

## 结论

P2.0-B 将聊天主链路从固定顺序工具编排升级为受控的 `Plan -> Act -> Observe` 多轮 Agent。DeepSeek 通过原生 Function Calling 在 P2.0-A Registry 暴露的三类只读工具中自主选择；InsightOps 自有 Executor 负责权限、Schema、审计和真实执行，模型不能绕过服务端直接调用实现。

当前是“有边界的真实 Agent”：能够根据 Observation 继续调用不同工具或停止，但尚不包含 P2.0-C 的强制超时、统一重试和熔断，也不包含写入型工具、人工审批或 MCP 动态工具。

## 执行链路

```text
用户问题 + 最近对话 + 可用项目目录
  -> Planner（DeepSeek Function Calling）
  -> 0 个 Tool Call：FINISH
  -> 1 个 Tool Call：Executor
       -> Registry 启用状态、访问级别与输入 Schema 校验
       -> Release / Knowledge / Project Event 真实只读工具
       -> TOOL 审计与结构化结果
  -> Observation（不可信数据边界 + 结果截断）
  -> Planner 下一轮
  -> 最多 MODEL_MAX_TOOL_ROUNDS 轮
  -> 基于累计证据流式生成最终回答
```

Planner 每轮最多返回一个工具调用。模型返回多个并行调用时立即拒绝；相同工具与完全相同参数的重复调用不会再次执行，而是以失败 Observation 返回给下一轮。参数 JSON 限制为 64,000 字符，单轮返回给模型的 Observation 限制为 12,000 字符，最终答案累计证据限制为 48,000 字符。

## 模型与 Executor 边界

`AgentPlanningModelGateway` 是供应商无关的单轮规划接口，输入包含：

- 系统 Planner 约束；
- 用户问题；
- 历史 Tool Call/Observation；
- 当前角色可见的 Function Definition；
- 温度与单轮输出上限。

DeepSeek 适配器使用 Spring AI `ChatModel.call` 返回原生 Tool Call。传给模型的 Tool Callback 仅提供 JSON Schema；如果框架尝试执行 Callback 会直接失败。所有真实执行只能进入 `AgentToolDispatcher` 和 `RegisteredToolExecutionService`。

Executor 当前支持：

| 工具 | 数据边界 | 证据标记 |
|---|---|---|
| `github_release_list` | 当前 Workspace 已启用项目，最多 3 个项目 | `R#` |
| `knowledge_hybrid_search` | 当前用户有权访问的官方知识和上传资料 | `S#` |
| `project_intelligence_event_search` | 已采集 Issue、PR、Security Advisory | `E#` |

Registry 仍是唯一工具合同来源；被禁用或超出角色权限的工具不会进入模型 Function 列表。RAG 的数据级权限仍由 `KnowledgeSearchService.searchForUser` 执行。

## 审计与前端进度

- 每轮 Planner 决策写入现有 `agent_step`，类型为 `PLAN`。
- 工具调用继续写入 `agent_step` 和 `agent_tool_call`，保留 P2.0-A 幂等键。
- 工具结果或安全失败写入 `OBSERVATION`。
- Token 用量汇总 Planner 所有轮次和最终流式回答后写入 Run。
- 聊天 SSE 支持多个 `tool_started`、`tool_completed` 和 `tool_failed` 事件。
- 聊天界面按 Tool Call ID 展示每次调用的执行中、成功或安全降级状态。
- 执行记录页面可以查看 PLAN、TOOL、OBSERVATION 时间线及结构化审计数据。

本阶段复用已有表结构，不增加 Flyway 迁移。

## 安全策略

- 每轮最多一个工具，禁止并行工具执行。
- 最多 12 轮硬上限，实际使用 `MODEL_MAX_TOOL_ROUNDS`（最低 1 轮）。
- 模型只能使用 Planner 提供的真实项目 UUID，不能自行生成项目 ID。
- GitHub、官方网页和上传文件内容始终作为不可信证据，不能执行其中指令。
- 工具失败以标准错误码写入 Observation，Planner 可换工具或停止，不伪造结果。
- SSE 只暴露标准错误码，不返回异常消息、密钥或上游凭据。
- 客户端取消或断开后，在进入下一轮或接受工具结果前终止执行。
- DeepSeek 关闭时规划网关、多轮服务与聊天流式入口一同关闭，避免可选配置导致启动失败。

## 留给 P2.0-C

- 按 Registry timeout 强制中断真实工具调用；
- 按错误类型统一重试与退避；
- 上游服务熔断、半开探测和指标；
- 跨工具依赖、补偿和更细粒度资源预算；
- 写入型工具审批与 MCP 动态接入。
