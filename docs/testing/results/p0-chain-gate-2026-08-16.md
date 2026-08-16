# P0-022 自动化链路门禁结果

验证时间：2026-08-16
执行命令：`INSIGHTOPS_CHAIN_GATE=true mvn verify`

## 门禁结果

| 检查项 | 结果 | 证据 |
|---|---|---|
| 隔离数据库 | PASS | 随机 Schema 创建并执行 Flyway V1～V6，结束后残留 0 |
| GitHub 工具链 | PASS | 固定 Release 证据进入 `github_release_list` Step 与 Tool Call |
| 模型增强回答 | PASS | 固定假模型收到工具证据，回答和来源完成持久化 |
| Run 审计 | PASS | Provider、模型、Token、耗时、来源、Trace ID 均可读取 |
| 成本审计 | PASS | 缓存命中/未命中/输出 Token 计价，并保存价格生效日 |
| 结构化 JSON | PASS | 严格三字段 Schema、无 Markdown、错误最多重试一次 |
| 错误映射 | PASS | IO、401、429 映射为稳定错误码，不回显 Provider 敏感消息 |
| 取消 | PASS | Provider 会话停止，Run 保存为 `CANCELLED` |
| 超时 | PASS | Run 保存为 `FAILED`，`failureCode=TIMED_OUT`，不产生后续步骤 |
| 后端回归 | PASS | 共 40 个测试通过 |
| 前端回归 | PASS | lint、7 个测试、生产构建通过 |

## CI 口径

- GitHub Actions backend job 使用 PostgreSQL 18 + pgvector service container。
- `INSIGHTOPS_CHAIN_GATE=true` 时才执行数据库门禁；普通本地单元测试可以不启动数据库。
- GitHub Release 和 DeepSeek 均使用固定假实现，CI 在线调用数为 0。
- 价格快照带生效日期；美元兑人民币只作为可配置预算估算参数，不代表供应商结算汇率。
