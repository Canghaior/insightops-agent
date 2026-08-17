# P1.4-D 混合 RAG 真实验收结果

验收日期：2026-08-17

## 环境

- PostgreSQL 18 + pgvector 0.8.5：`127.0.0.1:55432`
- 本地 Embedding：Ollama `bge-m3`，1024 维
- 最终生成：已有 DeepSeek Chat 配置
- 当前有效向量：6,135 / 6,135
- Flyway：V1–V14 全部成功

## 自动化回归

- Maven 全模块 `verify`（含真实 PostgreSQL/pgvector 链路门禁）：42 个测试套件、107 个测试通过，0 失败。
- 前端 ESLint：通过。
- 前端 Vitest：7 个测试文件、17 个测试通过。
- 前端生产构建：通过。
- 离线评测集：12 条，Spring AI / LangChain4j / Dify 各 4 条，结构与唯一性门禁通过。

## 真实问题

```text
请仅基于官方知识库说明 Spring AI EmbeddingModel 的核心接口和调用方法，并用 [S#] 标注证据。
```

最终复测：

- Run：`b9bcdfeb…`
- TraceId：`d7d4ac4d-b76b-40ba-bada-cd30c1683496`
- Run 状态：`SUCCEEDED`
- 工具：`knowledge_hybrid_search`
- 检索模式：`HYBRID`
- 融合候选：12
- 注入并持久化的结构化引用：6
- 页面展示模型：`bge-m3+fts`
- 最终回答 Token：2,802

首轮验收发现中英混合长问题被 PostgreSQL 按过严的 AND 条件解析，因此审计模式只有 `VECTOR`。修复为有效技术词 OR 查询后，同一问题真实复测为 `HYBRID`，避免了“页面声称混合、审计却只有向量”的假阳性。

## 页面与持久化

- 回答正文使用 `[S1]`–`[S6]` 标注结论。
- 页面显示 6 张可点击卡片，包含项目、章节、官方 URL 和融合得分。
- 刷新 `/chat` 后 6 张卡片从数据库完整恢复。
- `/runs` 的对应 Run 展示同一组结构化引用和 `knowledge_hybrid_search` 工具步骤。
- 快速点击“新建会话”时不会再被尚未完成的历史请求覆盖；复测为 0 条旧消息并显示空会话状态。

结论：P1.4-D 的向量 + 全文混合召回、确定性融合、结构化引用、历史恢复和审计链路均已在真实环境通过。
