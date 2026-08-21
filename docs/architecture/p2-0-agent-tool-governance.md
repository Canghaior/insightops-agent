# P2.0-D Agent 写工具治理与受控 MCP 扩展

日期：2026-08-22

## 结论

P2.0-D 为 Agent 增加了第一条真实写入链路 `user_memory_upsert`，并以持久化人工审批、幂等执行记录和可重复补偿约束副作用。同时，系统管理员或 Workspace Owner 可以登记受控 MCP 连接，Agent 只能调用明确启用且进入 allowlist 的只读工具。

本阶段不把“模型提出写操作”视为“写入成功”。模型只能创建待审批请求，原请求用户在审批中心确认后，服务端事务才会执行写入；聊天流会明确显示“尚未执行 · 等待你的审批”。

## 写工具审批生命周期

```text
模型选择 user_memory_upsert
  -> Registry 校验风险=MUTATING、审批=REQUIRED
  -> 保存 Tool Call / Step 为 WAITING_APPROVAL
  -> 保存 agent_tool_approval（30 分钟有效期）
  -> SSE tool_approval_required
  -> 原请求用户选择：
       approve -> 事务执行记忆写入 + 保存 effect before/after -> SUCCEEDED
       reject  -> REJECTED，不执行写入
       expire  -> EXPIRED，不执行写入
  -> 已执行请求可 compensate：
       新建记忆 -> 删除该记忆
       更新记忆 -> 恢复审批前快照
       重复补偿 -> 返回同一终态，不重复产生副作用
```

审批 API 按当前用户和 Workspace 隔离。其他用户即使知道审批 ID，也不能批准、拒绝或补偿该请求。审批动作、写入效果和补偿结果均持久化，不能只依赖浏览器状态。

## 幂等与补偿

Flyway V26 新增：

- `agent_tool_approval`：待审批载荷、申请人、状态、有效期和审批时间；
- `agent_tool_effect`：写入目标、幂等键、执行前/后快照、补偿状态和时间；
- `mcp_connection`：Workspace 级 MCP 连接、启用状态和工具 allowlist；
- Tool Call 新终态：`WAITING_APPROVAL`、`REJECTED`、`COMPENSATED`。

`user_memory_upsert` 的批准与效果写入在同一数据库事务中完成。重复批准同一请求只读取已有 Effect，不会重复创建记忆；重复补偿也只返回既有补偿结果。首次创建的记忆以删除作为补偿，更新既有记忆则恢复审批前快照。

## 审批与管理界面

- `/approvals`：查看待审批、已执行、已拒绝、已过期、失败和已补偿请求；执行批准、拒绝和补偿；跳转对应 Run。
- `/admin/agent-tools`：Owner/系统管理员管理 MCP 名称、Endpoint、工具 allowlist 和启用状态。
- `/chat`：收到 `tool_approval_required` 时展示待审批状态及审批中心入口，不把待审批 Observation 表述为已完成。

## 受控 MCP 首期能力

`mcp_read_call` 使用 JSON-RPC 2.0 `tools/call` 调用管理员登记的连接。执行前同时验证：

- 连接属于当前 Workspace 且已启用；
- 工具名在该连接 allowlist 中；
- Endpoint 是公共 `https://` 地址且端口为 443；
- 地址不含用户名、密码或 Fragment；
- DNS 解析结果不属于回环、私网、链路本地、组播或保留网段；
- HTTP 不跟随重定向；
- 响应大小受限且必须是合法 JSON-RPC JSON。

模型不能自行创建连接、扩大 allowlist 或传入任意 URL；Planner 只会看到当前 Workspace 已启用连接的 ID 和允许工具名。

## MCP 明确边界

当前是安全收敛的第一阶段，仅支持：

- 公共 HTTPS 443；
- 无凭据、无状态 JSON-RPC `tools/call`；
- JSON 响应；
- 管理员显式 allowlist 的只读工具。

当前不支持：

- 本地进程或 stdio MCP；
- 私网、localhost、任意端口或重定向目标；
- OAuth、API Key、客户端证书或其他 Secret 托管；
- SSE/Streamable HTTP 会话、会话恢复或服务端主动通知；
- MCP 自动发现、任意命令执行或写入型 MCP 工具。

如未来引入认证或有状态 MCP，必须先增加凭据保险库、连接级权限、会话生命周期、DNS 重绑定防护、审计脱敏和写工具审批策略，不能直接放宽本阶段网络边界。

## 剩余能力

- 跨工具依赖图和并行调度；
- 每工具 Token、调用次数和成本预算；
- 超出记忆写入的更多写工具及其差异化审批/补偿策略；
- 租户级工具包、连接健康探测和 MCP 合同发现；
- 认证型、会话型 MCP（仅在产品需求成立且安全设计完成后）。
