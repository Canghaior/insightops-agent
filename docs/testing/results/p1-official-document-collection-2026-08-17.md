# P1.4-A 官方文档采集验收结果

日期：2026-08-17

## 验收范围

- Spring AI Reference：`https://docs.spring.io/spring-ai/reference/`
- LangChain4j Documentation：`https://docs.langchain4j.dev/`
- Dify Documentation：`https://docs.dify.ai/en/home`
- 每个来源最多 200 页，最大深度 4，单页上限 8 MiB，请求间隔 500 ms。
- 本轮 `INTELLIGENCE_ANALYSIS_ENABLED=false`，没有调用 DeepSeek，也没有生成 Embedding。

## 最终采集结果

| 来源 | 状态 | 当前文档 | 当前修订 | 当前切片 | 最短/平均/最长字符 |
|---|---:|---:|---:|---:|---:|
| Spring AI | SUCCEEDED | 200 | 200 | 3,590 | 43 / 878 / 2,580 |
| LangChain4j | SUCCEEDED | 200 | 200 | 1,089 | 40 / 1,068 / 2,676 |
| Dify | SUCCEEDED | 200 | 200 | 1,456 | 54 / 1,003 / 2,662 |

合计 600 篇当前文档、6,135 个当前切片。三个来源连续失败数均为 0。

LangChain4j 首轮因旧的 2 MiB 单页上限返回 `CONTENT_TOO_LARGE`；将仍然受控的单页上限提高到 8 MiB 后，真实采集 200 页成功。

## 数据质量检查与修复

- Dify 每页重复的 `Documentation Index / llms.txt` 模板块已在正文清洗阶段删除，当前残留切片为 0。
- Spring AI 每页重复的稳定版本提示已删除，当前残留切片为 0。
- 切片器过滤只有 Markdown 标题、没有实质正文的切片；最终三源纯标题切片均为 0。
- 增加 `chunkPipelineVersion=2`。即使网页正文哈希不变，清洗或切片算法升级后也会自动重建旧切片。
- Spring AI 增量验证保持 200 篇正文和 288 条历史修订不变，重建 3,590 个当前切片并清除了 6 个历史纯标题切片。
- 高频重复抽样属于官方文档真实复用内容，例如 Dify API 密钥安全说明、Spring AI 不同版本的 Maven BOM 和重试配置；本轮不做可能损伤证据的模糊去重。
- 样本文档标题和 URL 均属于配置的官方 HTTPS 域名与允许路径。

## 页面状态展示

- `RUNNING`、`SUCCEEDED`、`FAILED`、首次等待和失败等待分别显示为“采集中”“已完成”“失败”“等待执行”“等待重试”。
- 有运行中任务时页面每 5 秒静默刷新；任务结束后自动停止轮询。
- 轮询不会反复闪烁全页加载状态，禁用按钮具有明确视觉反馈。

## 自动化验证

- `P0ChainDatabaseGateTest` 覆盖相同正文去重、切片管线版本升级和原位重建。
- Java 单元测试覆盖模板块清洗和纯标题切片过滤。
- PostgreSQL V1-V12 迁移、Java 全量门禁、前端 ESLint、Vitest 和生产构建纳入最终回归。

## 结论

P1.4-A 的三个官方来源均已真实采集成功，当前数据通过基础质量门禁。系统仍只保存原文、修订和结构化切片；Embedding、向量索引、混合检索和 RAG 回答属于后续 P1.4-B/C。
