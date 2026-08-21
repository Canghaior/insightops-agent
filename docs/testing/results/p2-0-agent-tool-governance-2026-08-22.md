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

- 功能提交：`1e419dc282bb98e048206e56aab8a9b6dd226e94`；Spring 构造器修复：`892f9097bf42ff84050c688455da00f9ad920722`。
- 首轮 CI Run `32511322913` 成功；首轮 Deploy Run `32511621610` 在 Spring 启动时发现 MCP 服务双构造器未显式选择，工作流自动回滚到上一健康镜像，生产服务未中断。
- 修复后 CI Run `32512128727` 的 backend、frontend 和三份镜像 Job 全部成功。
- 最终 Deploy production Run `32512407579` 成功；`Validate deployment secrets`、`Configure SSH`、`Deploy and verify` 全部通过，部署完整 SHA `892f9097bf42ff84050c688455da00f9ad920722`。
- 生产 Flyway 已验证 26 项迁移，Schema 为 V26；最终 Server、Worker、Web 及依赖服务通过部署健康检查。
- 公网首页、`/approvals` 和 `/admin/agent-tools` 均返回 HTTP 200；`/api/v1/system/status` 返回 Server `UP`、DeepSeek `deepseek-v4-flash` ready。
- 生产 Chunk `ApprovalsView-0WHZmn7E.js` 包含补偿动作，`AdminAgentToolsView-DWNrJp7_.js` 包含 MCP 连接 API，`ChatView-DwcmPowA.js` 包含 `tool_approval_required`，确认 P2.0-D 前端已发布。
- 未登录访问审批 API 和 MCP 管理 API 均返回 HTTP 401 / `UNAUTHENTICATED`，没有意外公开管理能力。
- 应用内浏览器自动化运行时在初始化阶段不可用，因此未在生产创建合成记忆数据；审批全生命周期、并发冲突保护、重复批准/补偿和拒绝流程已由真实 PostgreSQL 门禁完成。

## 结论

P2.0-D 的代码、数据库迁移、自动化门禁、失败回滚验证、修复后 CI、生产部署和公网即时验收均已完成。首个审批型写工具与受控只读 MCP 首期能力可以关闭；认证型/会话型 MCP、更多写工具策略和更细粒度成本预算进入后续阶段。
