# P0-021 Run 执行记录端到端验证

验证时间：2026-08-16
验证范围：Run 分页列表、状态筛选、详情、Step、Tool Call、来源和失败记录。

## 结果

| 检查项 | 结果 | 证据 |
|---|---|---|
| Flyway V5 | PASS | `agent_run.citations` 创建成功，历史成功 Run 来源完成回填 |
| 分页列表 API | PASS | 返回 7 条本地真实历史 Run |
| 成功 Run 详情 | PASS | 1 个 Step、1 个 Tool Call、2 个官方来源、1154 Token |
| 失败 Run 详情 | PASS | 展示 `FAILED` 和 `TEMPORARILY_UNAVAILABLE` |
| 状态筛选 | PASS | `FAILED` 筛选返回 2 条记录 |
| 工具审计 | PASS | 可展开 `github_release_list` 请求与结果 JSON |
| 深链接 | PASS | `/runs/{runId}` 可直接打开对应详情 |
| 后端回归 | PASS | Maven Reactor 共 30 个测试通过 |
| 前端回归 | PASS | lint、5 个测试和生产构建通过 |

## 口径

- 列表和详情均限定 Alpha Workspace，只提供读取能力。
- API 不返回 DeepSeek API Key、GitHub 响应 Header 或本地环境变量。
- 来源由 Run 独立持有，避免同一会话包含多轮问答时引用错配。
