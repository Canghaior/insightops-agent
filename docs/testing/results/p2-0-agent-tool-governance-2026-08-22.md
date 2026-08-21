# P2.0-D Agent 写工具治理与受控 MCP 验收

日期：2026-08-22

## 范围

- 第一条真实写工具 `user_memory_upsert` 的持久化人工审批。
- 写入 Effect 幂等、更新前快照和可重复补偿。
- Workspace 级 MCP 连接管理、工具 allowlist 和公共 HTTPS 只读调用。
- 审批中心、Agent 工具管理页和聊天待审批实时状态。

## 自动化结果

| 验证 | 结果 |
|---|---|
| `mvn -q test` | PASS |
| `INSIGHTOPS_CHAIN_GATE=true` 运行 `P0ChainDatabaseGateTest` | PASS：真实 PostgreSQL、Flyway V1-V26 全量迁移通过 |
| 审批服务定向测试 | PASS：请求、载荷校验和待审批会话状态 |
| MCP 服务定向测试 | PASS：allowlist、HTTPS/端口、私网地址、重定向与 JSON-RPC 错误边界 |
| `npm run test` | PASS：17 个测试文件、37 项测试 |
| `npm run lint` | PASS |
| `npm run build` | PASS |

## 数据库与副作用验收

- V26 新增审批、Effect 和 MCP 连接表，并扩展 Tool Call 状态约束。
- 真实 PostgreSQL 门禁完成 `WAITING_APPROVAL -> SUCCEEDED -> COMPENSATED` 全生命周期。
- 同一审批重复批准不重复写入；同一 Effect 重复补偿不重复改变数据。
- 新建记忆补偿后删除；更新记忆补偿后恢复写入前快照。
- 拒绝审批不产生记忆，Tool Call 和 Step 保存为 `REJECTED`。

## 权限与交互验收

- 审批列表和动作绑定当前请求用户及 Workspace。
- MCP 管理只允许 Workspace Owner 或系统管理员访问。
- 聊天 SSE 返回 `tool_approval_required`，只提供审批摘要、ID 和有效期，不携带敏感内部异常。
- 前端明确区分“等待审批”和“执行完成”，审批中心支持批准、拒绝、补偿及 Run 跳转。

## MCP 安全验收

- 连接必须是公共 HTTPS 443，拒绝凭据、Fragment、localhost、私网和保留地址。
- HTTP 客户端不跟随重定向，响应体有大小上限。
- Agent 只能使用当前 Workspace 已启用连接及其 allowlist 工具，不能自选 Endpoint。
- 当前仅支持无凭据、无状态 JSON-RPC `tools/call` 和 JSON 响应；不支持 stdio、SSE 会话、认证或写入 MCP。

## 生产验收

生产提交、CI、部署 Run、健康检查和静态资源证据将在本次发布完成后写入本节。
