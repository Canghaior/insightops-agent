# P2.0-A Agent Tool Registry

日期：2026-08-21

## 结论

P2.0-A 建立了统一 Tool Registry，并把当前生产研究链路中的三类真实工具迁移到统一注册、参数校验和审计会话。该阶段提供后续 Planner、Executor 和模型 Function Calling 所需的稳定工具合同，但不改变当前后端受控编排，也不宣称已经实现自主多轮 Agent。

## 注册合同

每个工具必须声明：

- 稳定名称与合同版本；
- 启用状态；
- 最低访问级别；
- 只读或写入风险级别；
- 是否需要人工审批；
- 建议超时和最大审计结果字符数；
- 封闭的输入、输出 JSON Schema。

工具名和参数名必须符合固定命名规则；重复工具名、未知工具、禁用工具、权限不足、未知参数、缺少必填参数、类型错误和范围越界均在真实工具调用前拒绝。任何未来写入型工具在注册时必须声明 `REQUIRED` 审批策略，否则应用无法完成该工具定义的构造。

## 当前内置工具

| 工具 | 用途 | 步骤 | 风险 | 最低权限 | 审批 |
|---|---|---:|---|---|---|
| `github_release_list` | 查询 Workspace 已启用项目的 GitHub 官方 Release | 1 | `READ_ONLY` | `WORKSPACE_MEMBER` | 不需要 |
| `knowledge_hybrid_search` | 在用户可见的官方资料和上传文件中执行混合检索 | 2 | `READ_ONLY` | `WORKSPACE_MEMBER` | 不需要 |
| `project_intelligence_event_search` | 查询已采集的 Issue、PR 和 Security Advisory | 3 | `READ_ONLY` | `WORKSPACE_MEMBER` | 不需要 |

RAG 工具的 Registry 启用状态与 `KnowledgeRagProperties.enabled` 保持一致；禁用时不会出现在可用目录或模型工具 Schema 中。

## 统一执行与审计

`RegisteredToolExecutionService` 是当前内置工具的统一入口：

1. 从 Registry 解析并确认工具已启用；
2. 按调用者访问级别验证权限；
3. 根据输入合同生成不可变参数副本；
4. 生成 Step ID、Tool Call ID 和审计幂等键；
5. 写入现有 `agent_step`、`agent_tool_call` 审计链；
6. 统一记录结果、错误码和耗时；
7. 校验输出 Schema、限制写入审计表的结果大小，并拒绝同一会话重复完成。

幂等键格式为：

```text
{runId}:{toolName}:{round}:{invocationNo}
```

数据库已有唯一约束，因此同一 Run、工具、轮次和调用序号不能重复落库。本阶段没有新增数据库迁移。

## 工具目录 API

已登录用户可以读取：

```http
GET /api/v1/agent/tools
```

响应只包含当前角色可用且已启用的工具合同，包括输入/输出 Schema、权限、风险、审批策略、建议超时和审计结果上限；不返回实现类、密钥、内部地址或环境变量。Registry 同时可生成供应商无关的 `type=function` Schema，供 P2.0-B 接入 DeepSeek Function Calling。

## 安全边界

- 当前三类内置工具全部只读，不产生外部写操作。
- Registry 只接收服务端路由或后续 Executor 提供的结构化参数，拒绝额外字段。
- 用户可见上传资料的 RAG 权限仍由 `KnowledgeSearchService.searchForUser` 强制执行，Registry 不绕过数据级授权。
- GitHub 与知识库文本继续按不可信外部数据处理，不能覆盖系统指令。
- API 目录按 Workspace/System 角色过滤，未授权工具不会暴露为可调用项。

## 本阶段明确未完成

- 模型自主选择工具；
- Planner、Executor 和 Observation 数据模型；
- Plan-Act-Observe 多轮工具循环；
- 建议超时的强制中断、统一重试和熔断；
- 写入型工具的真实人工审批工作流；
- MCP 工具动态接入；

这些内容分别进入 P2.0-B、P2.0-C 和后续 MCP/审批阶段，不能用本次 Registry 完成状态替代。

## 后续状态（2026-08-22）

上述历史边界已由后续阶段逐步关闭：P2.0-B 完成模型 Function Calling 和多轮循环，P2.0-C 完成可靠性与监控，P2.0-D 完成首个写工具 `user_memory_upsert` 的持久化审批、幂等 Effect、补偿，以及公共 HTTPS + allowlist 的受控只读 MCP 首期能力。详细边界见 `p2-0-agent-tool-governance.md`。

## 验证范围

- Registry 定义、封闭 Schema、参数类型与范围校验；
- 重复、未知、禁用和越权工具拒绝；
- 供应商无关 Function Schema；
- 工具目录 API 与角色映射；
- 审计幂等键、成功/失败和重复完成保护；
- Release、RAG、项目事件三条真实服务迁移；
- 生产数据库门禁中的 Release 工具装配。
