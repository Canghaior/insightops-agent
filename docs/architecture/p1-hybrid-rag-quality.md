# P1.4-D 混合 RAG 质量工程

## 目标

P1.4-D 在 P1.4-C 可追溯 RAG 闭环上解决三项质量问题：单路语义检索对精确技术词不够稳定、历史记录只保存 URL、缺少可版本化的离线问题集。本阶段不引入新的云端模型或 API Key。

## 检索链路

一次知识检索使用两路候选：

1. Ollama `bge-m3` 生成 1024 维查询向量，在 pgvector HNSW 索引中做余弦检索。
2. 从中英混合问题中提取最多 8 个有效 ASCII 技术词，以 OR 语义查询 PostgreSQL `simple` 全文索引。
3. `HybridSearchRanker` 按 chunk ID 合并候选，使用加权 Reciprocal Rank Fusion：向量权重 0.65、全文权重 0.35、`k=60`。
4. 问题明确出现 `spring-ai`、`langchain4j` 或 `dify` 时，对对应项目施加 1.08 的确定性提示加权。
5. P1.4-C 的单文档配额、文档去重、最多 6 条证据和 12,000 字符上下文预算继续生效。

实际执行模式写入 `retrieval_trace.retrieval_mode`：两路均命中为 `HYBRID`，只有向量命中为 `VECTOR`，Embedding 不可用但全文命中为 `KEYWORD`。页面显示的检索模型必须与该模式一致，避免把单路召回误标为混合召回。

## 全文索引

V14 在 `knowledge_chunk.content` 的 `to_tsvector('simple', ...)` 表达式上创建 GIN 索引。选择 `simple` 配置是为了保留 Java 类名、配置键和项目名，不依赖额外 PostgreSQL 语言扩展。

自然语言停用词和两字符以下词会被忽略。例如：

```text
请说明 Spring AI EmbeddingModel 的调用方法
=> "spring" OR "embeddingmodel"
```

没有可用技术词时，系统保持向量检索，不构造空全文查询。

## 结构化引用

V14 为 `agent_run` 和 `conversation_message` 增加 `citation_details JSONB`，每条引用包含：

- `label`：回答中的 `S1`、`S2` 或 Release 证据的 `R1`；
- `title`、`project`、`heading`；
- `url`：可点击的官方来源；
- `sourceType`：`OFFICIAL_DOCUMENT` 或 `GITHUB_RELEASE`；
- `score`：RAG 融合得分，Release 证据可为空。

原有 `citations` URL 数组继续写入，保证旧会话、旧 Run 和旧客户端向后兼容。SSE 完成事件同时返回 `sources` 与 `citations`；聊天历史和执行记录优先展示结构化引用，旧记录自动回退为 URL 列表。

## 离线评测集

`docs/evals/p1-rag-questions.jsonl` 当前包含 12 条已审核问题，Spring AI、LangChain4j、Dify 各 4 条。每条记录声明问题、期望项目、关键术语和是否必须返回证据。自动化测试验证 JSONL 可解析、ID/问题唯一、字段完整以及 4/4/4 平衡。

当前门禁验证数据质量与融合算法的确定性；下一步可在不改变数据格式的前提下增加 Recall@K、MRR、引用覆盖率、忠实度和无答案正确率。

## 降级与边界

- 向量服务故障：全文有结果时使用 `KEYWORD`。
- 全文查询故障或没有有效技术词：向量有结果时使用 `VECTOR`。
- 两路都不可用：RAG 工具记录失败，聊天链路按既有策略安全降级。
- 本阶段不增加 Cross-Encoder，也不为切片或重排购买新 Key。
