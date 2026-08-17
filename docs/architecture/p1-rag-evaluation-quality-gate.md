# P1.4-E RAG 自动化质量评测

## 目标

P1.4-E 把 P1.4-D 的静态题库升级为可重复执行、可落库、可判定通过与否的质量门禁。管理员不需要 Navicat 或命令行，在知识库页面点击一次即可完成检索、引用和忠实度评测。

## 评测集

版本化文件 `docs/evals/p1-rag-questions.jsonl` 共 15 题：

- Spring AI、LangChain4j、Dify 各 4 道可回答题；
- Kubernetes、FastAPI、React 各 1 道越界问题；
- 每道可回答题固定期望项目和检索必须命中的术语；
- 越界题的期望结果是不提供文档证据并明确拒答。

构建时题库被复制到 Server classpath，因此本地脚本、可执行 JAR 和 CI 使用同一份数据，不依赖运行目录。

## 执行链路

1. 对 15 题逐题执行工作区隔离的混合检索，取前 10 条结果。
2. 计算项目 Recall@10、MRR、关键术语覆盖和三道越界题的拒答准确率。
3. 默认从三个项目各抽一题，向 DeepSeek 提供最多 6 条官方证据并要求逐项使用 `[S#]`。
4. 计算引用编号准确率和证据引用覆盖率。
5. 再由 DeepSeek 以严格 JSON 协议评判回答事实是否能由证据直接支持，产生 0～1 忠实度分数和理由。
6. 按固定阈值产生 `PASSED` 或 `FAILED`，汇总和 15 条明细写入 PostgreSQL。

不需要新 Key：回答器和忠实度裁判复用现有 DeepSeek API Key；15 道检索继续使用本机 Ollama `bge-m3`。管理员也可把生成抽样设为 0，只跑不产生云端费用的检索门禁。

## 默认门禁

| 指标 | 最低值 |
|---|---:|
| Recall@10 | 0.90 |
| MRR | 0.65 |
| 术语覆盖 | 0.55 |
| 拒答准确率 | 1.00 |
| 引用准确率 | 0.90 |
| 引用覆盖率 | 0.50 |
| 忠实度 | 0.75 |

阈值可通过 `RAG_EVAL_MIN_*` 环境变量调整，但题库和历史结果不会被覆盖。

## 安全与可追溯性

- `KnowledgeAnswerabilityPolicy` 只允许题目指向 Spring AI、LangChain4j 或 Dify，且前 5 条检索结果必须命中对应项目；追问由聊天链路拼接上一轮用户问题后再判定。
- 不满足条件时，模型系统提示强制回答“当前官方证据不足”，且不注入来源。
- V15 新增 `rag_evaluation_run` 与 `rag_evaluation_case`，保存阈值判定所需全部指标、模型名、来源 URL、回答和裁判理由。
- 同一 Server 进程一次只允许一个评测任务，避免误触造成重复模型费用。
- 管理接口需要 `SYSTEM_ADMIN`；普通用户不能启动或读取评测报告。

## 接口

```text
GET  /api/v1/admin/knowledge/evaluations/latest
POST /api/v1/admin/knowledge/evaluations
     {"generationSampleSize":3,"judgeFaithfulness":true}
```

页面显示最近一次门禁状态、七项指标和未达标题目。真实验收记录见 `docs/testing/results/p1-rag-evaluation-e2e-2026-08-17.md`。
