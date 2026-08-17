# P1.4-C 可追溯 RAG 研究问答

## 目标

P1.4-C 将 P1.4-B 的本地 `bge-m3` 向量检索接入研究问答，使聊天链路从“模型凭已有知识回答”升级为：

```text
用户问题
  -> 本地 bge-m3 查询向量
  -> PostgreSQL pgvector 工作区隔离检索
  -> 证据去重与上下文预算
  -> DeepSeek 流式生成
  -> 官方来源、Run、Step、Tool Call、retrieval_trace 持久化
```

本阶段不增加云端 Embedding Key。查询向量仍由本机 Ollama `bge-m3` 生成，只有最终回答调用已有 DeepSeek Chat API。

## 检索与上下文策略

- 默认召回 12 个候选切片，最低相似度沿用 `EMBEDDING_MINIMUM_SCORE`。
- 最多向模型提供 6 个证据切片，同一官方页面最多 2 个切片。
- 证据正文总预算默认 12,000 字符，防止无界扩大 Prompt 和模型费用。
- 证据按检索排序保留，并使用 `[S1]`、`[S2]` 等稳定编号。
- 模糊追问（例如“这个版本”“它”“相比上一个”）会把最近一条用户问题并入检索查询；普通新问题只检索当前问题。
- 官方文档正文仍视为不可信外部数据，只能作为事实材料，不能覆盖系统规则。

当前是“向量召回 + 确定性去重/裁剪”，尚未加入关键词混合召回或独立 Cross-Encoder 重排模型。它已经形成可用的最小 RAG 闭环，同时保留后续质量升级空间。

## 运行与降级

RAG 由 `RAG_ENABLED` 独立控制。启用时，每次研究问答会执行 `knowledge_vector_search`：

- 检索成功：把证据注入系统上下文，最终 Run 保存去重后的官方 URL。
- 没有命中：继续调用 DeepSeek，但不提供伪造证据。
- Ollama/Embedding 暂时不可用：Tool Call 标记失败，页面提示安全降级，聊天仍可继续。
- 来源白名单仅接受登记的 Spring AI、LangChain4j、Dify 官方 HTTPS 文档域名，以及既有 GitHub Release tag URL。

本地默认配置：

```properties
RAG_ENABLED=true
RAG_CANDIDATE_LIMIT=12
RAG_MAX_EVIDENCE_CHUNKS=6
RAG_MAX_CHUNKS_PER_DOCUMENT=2
RAG_MAX_CONTEXT_CHARACTERS=12000
```

`.env.example` 保持默认关闭，避免未安装 Ollama 或尚未完成向量化的环境误启用。

## 审计与恢复

- `retrieval_trace.run_id` 关联本次 `agent_run`，记录原始检索候选和耗时。
- `agent_step` / `tool_call` 保存 `knowledge_vector_search` 的请求、选择结果、模型和分数。
- `agent_run.citations` 与助手消息的 `citations` 保存最终提供给模型的官方 URL。
- 会话历史接口返回消息引用；页面刷新后回答和官方来源同时恢复。
- 前端 SSE 会显示检索中、选中证据数、Embedding 模型及降级状态。

## 后续质量增强

1. 加入关键词/BM25 与向量的混合召回。
2. 建立离线 RAG 评测集，覆盖命中率、引用正确率、忠实度和无答案场景。
3. 评估本地重排模型，只有在质量收益明确时才引入额外模型与资源成本。
4. 将 `[S#]` 从正文编号升级为可点击的结构化引用卡片。
