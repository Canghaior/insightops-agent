# P0-020 GitHub Release 工具端到端验证

- 日期：2026-08-16
- GitHub API：REST API，版本 `2026-03-10`
- 工具：`github_release_list`
- 模型：DeepSeek `deepseek-v4-flash`
- 数据库：PostgreSQL 18.4，Flyway V4

## 验收结果

| 检查 | 结果 | 备注 |
|---|---|---|
| 仓库白名单 | PASS | 只允许 Spring AI、LangChain4j、Dify；未知项目不路由、不请求 |
| 官方 API Header | PASS | `Accept`、`User-Agent`、`X-GitHub-Api-Version` 均已设置 |
| 真实 Release 查询 | PASS | Spring AI 返回 2 条符合正式版本口径的 Release |
| SSE 工具进度 | PASS | 收到 `tool_started`、`tool_completed`、`completed` |
| 证据增强回答 | PASS | 最终回答包含官方 GitHub Release URL |
| Agent Run | PASS | `SUCCEEDED`，`tool_rounds=1`，模型和 Token 已保存 |
| Agent Step | PASS | 第 1 步、类型 `TOOL`、状态 `SUCCEEDED` |
| Tool Call | PASS | 名称 `github_release_list`、状态 `SUCCEEDED`、结果 2 条、耗时已保存 |
| 消息引用 | PASS | ASSISTANT 消息保存 2 条官方来源 |
| 浏览器端到端 | PASS | 显示工具名、Release 数量、官方来源和模型指标 |
| 浏览器控制台 | PASS | 无 warning/error |

本次验证问题只要求最新正式版本、发布日期和官方链接。工具输入/输出均为受控 JSON；未记录 DeepSeek Key，也未把 GitHub 响应错误正文暴露给客户端。
