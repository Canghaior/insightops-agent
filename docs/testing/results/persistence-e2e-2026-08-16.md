# P0-019 持久化端到端验证

- 日期：2026-08-16
- 环境：Windows 本地开发环境
- 数据库：PostgreSQL 18.4 + pgvector 0.8.5
- Flyway：V1～V3 全部成功
- 模型：DeepSeek `deepseek-v4-flash`

## 验收结果

| 检查 | 结果 | 备注 |
|---|---|---|
| 新建会话 | PASS | 首次请求创建会话，`started` SSE 返回 `sessionId` |
| 续接会话 | PASS | 第二次请求复用相同 `sessionId` |
| 成功 Run | PASS | 状态 `SUCCEEDED`，保存回答、Provider、模型、输入/输出 Token 和完成时间 |
| 取消 Run | PASS | 第 1 个增量 Chunk 后取消，状态 `CANCELLED`，保存部分回答和完成时间 |
| 消息顺序 | PASS | 同一会话为 USER 1、ASSISTANT 2、USER 3 |
| 取消消息边界 | PASS | 取消的部分回答不写成正式 ASSISTANT 消息 |
| 浏览器端到端 | PASS | 页面显示生成完成、会话短 ID、RunId、TraceId 和模型指标 |
| 浏览器控制台 | PASS | 无 warning/error |

自动回归覆盖成功、取消和 Provider 失败三种持久化编排。在线验收数据保留在本地开发数据库，作为执行记录核查样本。
