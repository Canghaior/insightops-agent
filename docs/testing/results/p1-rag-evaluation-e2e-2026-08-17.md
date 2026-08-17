# P1.4-E RAG 自动化评测验收（2026-08-17）

## 环境

- PostgreSQL + pgvector：本机 Docker，6,135 个已向量化官方文档切片；
- Embedding：本机 Ollama `bge-m3`，1024 维；
- 生成与裁判：`deepseek-v4-flash`，复用现有本地 API Key；
- 数据集：`p1-rag-questions-v2`，15 题；
- 评测 Run：`75d92ce8-7cc5-481b-aed0-ff45674bb7b4`。

## 真实结果

| 指标 | 结果 | 门禁 |
|---|---:|---:|
| Recall@10 | 1.000 | ≥ 0.90 |
| MRR | 1.000 | ≥ 0.65 |
| 术语覆盖 | 0.917 | ≥ 0.55 |
| 拒答准确率 | 1.000 | ≥ 1.00 |
| 引用准确率 | 1.000 | ≥ 0.90 |
| 引用覆盖率 | 0.778 | ≥ 0.50 |
| 忠实度 | 1.000 | ≥ 0.75 |

最终状态为 `PASSED`。Spring AI、LangChain4j、Dify 的 12 道题均在第一名命中期望项目；Kubernetes、FastAPI、React 三道越界题均判定不可回答。三个项目各抽一题生成答案并接受 DeepSeek 忠实度裁判。

## 持久化核验

数据库只读查询确认：

- `rag_evaluation_run.case_count = 15`；
- `rag_evaluation_case` 实际保存 15 条，题目 ID 无重复；
- 汇总指标、模型名、每题 Top 项目、来源 URL、生成回答和裁判结果可恢复；
- 管理页面刷新后能从数据库加载最近一次报告。

## 自动化回归

- Maven 全模块 `verify` 通过；
- 题库结构、4/4/4 + 3 分布通过自动化测试；
- 项目判定、安全拒答、Recall/MRR/术语覆盖、引用计算和阈值聚合通过单元测试；
- Web ESLint 与生产构建通过；
- 可执行 JAR 已验证包含 `BOOT-INF/classes/evals/p1-rag-questions.jsonl`。
