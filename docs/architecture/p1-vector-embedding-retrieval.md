# P1.4-B 本地 Embedding 与向量检索

## 目标

P1.4-B 将 P1.4-A 采集的官方文档切片转换为本地向量，并提供可审计的工作区级语义检索。该阶段不生成 RAG 最终答案，也不调用 DeepSeek。

## 固定技术方案

- Embedding 服务：本机 Ollama
- Embedding 模型：`bge-m3`
- 向量维度：1024
- 向量数据库：现有 PostgreSQL 18 + pgvector 0.8.5
- 距离函数：cosine distance，返回值转换为相似度 `1 - distance`
- 索引：HNSW + `vector_cosine_ops`
- 数据目录：`D:\AGENT-JUNDAO\models\ollama`

## 数据模型

V13 新增 `knowledge_embedding`。原始切片继续保存在 `knowledge_chunk`，向量单独保存并通过 `chunk_id` 关联。

每条向量记录包含：

- provider、模型名和维度
- `PENDING / RUNNING / SUCCEEDED / RETRY_WAIT / FAILED` 状态
- 尝试次数、下次重试时间和 Worker 租约
- 最后错误、创建时间和更新时间
- 1024 维向量

主键为 `(chunk_id, embedding_model)`，因此未来切换模型时可以并行回填新模型，不覆盖原模型数据。

## 执行链路

1. Worker 扫描所有启用知识源的当前文档切片，为指定模型补建待处理记录。
2. 使用数据库行锁与 `SKIP LOCKED` 批量领取任务，避免多 Worker 重复处理。
3. 通过 Spring AI `EmbeddingModel` 调用本机 Ollama。
4. 校验返回数量、维度和有限数值后写入 pgvector。
5. 失败任务等待重试；超过最大次数进入 `FAILED`，管理员可重新入队。
6. 搜索请求先生成查询向量，再只检索当前文档版本和当前工作区。
7. 每次检索写入 `retrieval_trace`，保留查询、模式、耗时和结果摘要。

## API

- `GET /api/v1/admin/knowledge/embeddings`：系统管理员查看总体和分来源进度。
- `POST /api/v1/admin/knowledge/embeddings/retry`：系统管理员重试最终失败任务。
- `POST /api/v1/knowledge/search`：所有已登录用户执行工作区级语义检索。

搜索请求示例：

```json
{
  "query": "Spring AI 如何配置 Ollama Embedding 模型？",
  "limit": 8
}
```

## 本机配置

```properties
EMBEDDING_ENABLED=true
SPRING_AI_MODEL_EMBEDDING=ollama
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=bge-m3
EMBEDDING_DIMENSIONS=1024
EMBEDDING_BASE_URL=http://127.0.0.1:11434
```

Ollama 进程必须能读取 `OLLAMA_MODELS=D:\AGENT-JUNDAO\models\ollama`。`.env` 是本机文件，不提交到 Git。

## 2026-08-17 验收结果

- 当前文档：600
- 当前切片：6,135
- 成功向量：6,135
- 最终失败：0
- Spring AI 查询 Top 1：`Embeddings Model API`，相似度 0.6900
- LangChain4j 查询 Top 1：`AI Services`，相似度 0.7049
- Dify 查询 Top 1：`Create Chunks`，相似度 0.6166
- 三组查询均命中对应项目的官方文档域名
- 页面显示 100% 完成，页面检索成功，浏览器控制台无错误

## P1.4-C 边界

下一阶段再将检索接入研究问答：查询改写、混合检索、重排、上下文预算、引用拼装和 DeepSeek 生成均属于 P1.4-C。本阶段只提供可靠的向量底座和可独立验收的检索 API。
