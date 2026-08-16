# DeepSeek Smoke Test 结果

- 日期：2026-08-16
- 环境：Windows 本地开发环境
- Spring Boot：4.1.0
- Spring AI：2.0.0
- Provider：DeepSeek 官方 API
- 模型：`deepseek-v4-flash`
- 思考模式：关闭
- Java 适配：Spring AI OpenAI-compatible Starter

## P0-017 验收结果

| 检查 | 结果 | 耗时 | Token | 备注 |
|---|---|---:|---:|---|
| 官方模型列表 | PASS | - | 0 | 返回 `deepseek-v4-flash`、`deepseek-v4-pro` |
| 固定普通问答 | PASS | 1433 ms | 输入 34 / 输出 9 / 总计 43 | 精确返回 `DEEPSEEK_SMOKE_OK` |
| Provider/模型元数据 | PASS | - | - | `deepseek` / `deepseek-v4-flash` |
| TraceId | PASS | - | - | 响应中存在 |
| 思考模式关闭 | PASS | - | - | 请求适配测试断言 `thinking.type=disabled` |
| Key 泄漏检查 | PASS | - | - | Key 未进入源码、响应、测试报告或日志 |

## 兼容性发现

Spring AI 2.0 原生 DeepSeek Starter 的请求记录不包含 DeepSeek V4 的 `thinking` 字段。第一次兼容性请求因此消耗 32 个输出 Token 后只返回截断内容 `DEEP`，未计为通过。项目随后按 ADR 备选方案切换到 Spring AI OpenAI-compatible Starter，通过顶层 `extraBody` 发送 `thinking.type=disabled`，最终验证通过。

本轮两次真正到达模型的验证请求合计消耗 188 Tokens（兼容性探测 145，最终通过请求 43）。

## P0-018 验收结果

| 检查 | 结果 | 耗时 | Token | 备注 |
|---|---|---:|---:|---|
| 真实 SSE 流式输出 | PASS | 662 ms | 输入 94 / 输出 22 / 总计 116 | 收到 22 个有序增量 Chunk 和最终完成事件 |
| 首 Token | PASS | 282 ms | - | 完成事件记录首 Token 时间 |
| 用户取消 | PASS | - | - | 第 1 个 Chunk 后取消，接口返回 200，并收到 `cancelled` |
| 取消边界 | PASS | - | - | 取消后 0 个新增 Chunk，且未收到 `completed` |
| 浏览器端到端 | PASS | 900 ms | 总计 128 | 页面先增量显示，再展示完成状态、模型、Token、RunId 和 TraceId |
| 浏览器控制台 | PASS | - | - | 无 warning/error |

在线请求只用于显式验收，不进入普通单元测试或 CI。取消测试未记录回答正文，也未输出任何密钥。

## P0-020 验收结果

| 检查 | 结果 | 备注 |
|---|---|---|
| 工具路由 | PASS | Release 问题命中 `github_release_list`；普通架构问题不调用工具 |
| GitHub 官方 API | PASS | 白名单仓库真实返回 2 条已发布 Release |
| 工具增强生成 | PASS | DeepSeek 只基于工具证据回答，并包含官方 GitHub URL |
| Agent Step / Tool Call | PASS | Step、Tool Call 均为 `SUCCEEDED`，请求/结果 JSON 和耗时已保存 |
| 引用保存 | PASS | ASSISTANT 消息保存 2 条官方 Release 引用 |
| 页面展示 | PASS | 显示工具名、Release 数量和可点击官方来源 |

P0 按 ADR-003 使用项目控制的工具循环和严格仓库白名单，不把仓库选择权交给模型。动态模型原生 Function Calling 作为后续扩展，不阻塞第一条 Release 工具链。

## 后续测试映射

| 测试 | 状态 | 对应任务 |
|---|---|---|
| 真实流式输出与取消 | PASS | `P0-018` |
| GitHub Release 工具链 | PASS | `P0-020` |
| JSON、错误场景和完整成本门禁 | PASS | `P0-022` |
