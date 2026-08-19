# P1.4 RAG 生产验收（2026-08-19）

## 结论

P1.4 生产 RAG 验收通过并关闭。三份官方知识库、当前切片 Embedding、混合检索、15 题质量门禁、真实问答引用、越界拒答、增量重采集以及采集任务可观测性均已在生产环境形成闭环。

- 生产地址：<https://insightops.canghaior.com>
- 生产提交：`944955366af71c17726ae677b3ef44bb070d217d`
- RAG 评测 Run：`768bb266-f75c-4f4e-8c90-d00c683ea5dc`
- 受控采集 Job：`5734015c-bc10-4c2e-9bbc-ce95a880e699`

## 部署与运行环境

- GitHub Actions 构建 Run `32264771666` 的后端、前端和 Server、Worker、Web 三个镜像任务全部成功。
- 生产服务器通过 `scripts/deploy-prod.sh` 部署目标提交；部署前生成数据库备份 `backups/insightops-20260819T145821Z.dump`。
- PostgreSQL、Ollama、Server、Worker、Web、Caddy 六个服务均通过健康检查。
- Flyway `V16__add_knowledge_collection_progress_and_leases.sql` 执行成功。
- 公网首页和 `/api/v1/system/status` 均返回 HTTP 200，DeepSeek `deepseek-v4-flash` 状态为 ready。
- 部署后 15 分钟 Server、Worker 日志未发现 `ERROR`、`Exception` 或 `FAILED`。

GitHub Actions 的生产部署 Workflow Run `32265167106` 在部署前密钥校验阶段失败，未连接或修改生产服务器。本次部署改用现有 OrcaTerm 管理会话完成；补齐 GitHub `production` Environment Secrets 后仍需单独完成一次 Actions 生产部署验收。

## 知识库与 Embedding

管理页面按当前文档版本统计：

| 来源 | 当前文档 | 当前切片 | 最近任务 | 连续失败 |
|---|---:|---:|---|---:|
| Spring AI | 200 | 3,590 | SUCCEEDED | 0 |
| LangChain4j | 202 | 1,088 | SUCCEEDED | 0 |
| Dify | 200 | 1,456 | SUCCEEDED | 0 |
| 合计 | 602 | 6,134 | 全部成功 | 0 |

LangChain4j 在 2026-08-19 的增量采集中新增 2 篇当前文档、变更 4 篇文档并重建 238 个切片，因此当前口径由上一轮 `200 / 1,089` 更新为 `202 / 1,088`。历史修订与旧切片继续保留用于审计，但管理页和 RAG 只统计、检索当前修订。

当前 6,134 个切片的 `bge-m3` Embedding 完成率为 100%；待处理、运行中、等待重试和失败均为 0。

## 采集进度、心跳与租约

在生产管理页手动触发 Spring AI 重采集并观察完整生命周期：

1. 等待阶段显示“等待执行”和计划执行时间。
2. 运行阶段先后观察到 `52 / 112 / 51`、`95 / 114 / 94`、`154 / 550 / 152` 的已访问、已发现和有效页面计数。
3. 页面持续展示当前 URL；采集过程中 URL 随访问页面变化。
4. 心跳从 `23:15:34` 更新到 `23:16:03`、`23:16:43`，租约到期时间同步从 `23:25:34` 续期到 `23:26:03`、`23:26:43`。
5. 最终任务为 `SUCCEEDED`：有效页面 200、已访问 201、已发现 552、上限 200。
6. 最后 URL 为 `https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs/apache-cassandra.html`。
7. 重采集新增文档 0、变更文档 0、未变化文档 200、新切片 0；当前数据仍为 `200 / 3,590`，幂等性通过。

数据库保留最终 `heartbeat_at`、`lease_expires_at`、`current_url`、开始时间和完成时间。代码与数据库门禁同时覆盖过期租约和 fencing token，旧 Worker 不能完成或覆盖已经失去租约的任务。

## 15 题 RAG 质量门禁

本轮在生产管理页重新运行 `p1-rag-questions-v2`，15 题全部持久化，最终状态为 `PASSED`：

| 指标 | 生产结果 | 门禁 | 结果 |
|---|---:|---:|---|
| Recall@10 | 1.000 | >= 0.90 | 通过 |
| MRR | 1.000 | >= 0.65 | 通过 |
| 术语覆盖 | 0.917 | >= 0.55 | 通过 |
| 拒答准确率 | 1.000 | >= 1.00 | 通过 |
| 引用准确率 | 1.000 | >= 0.90 | 通过 |
| 引用覆盖率 | 0.889 | >= 0.50 | 通过 |
| 忠实度 | 0.967 | >= 0.75 | 通过 |

## 真实检索、问答与边界

- 管理页语义检索“Spring AI 如何配置 Ollama Embedding？”返回 8 条结果，第一名命中 Spring AI `Ollama Embeddings`，融合检索耗时 476 ms。
- 真实问答“用三点解释 Spring AI 对 Java AI 应用开发的价值，并为每一点附上官方来源。”完成 Run `b567bd63`，选取 6 条 RAG 证据，答案使用 `[S1]`、`[S2]` 并展示结构化官方引用。
- 该回答首 Token 1,149 ms，总耗时 5,572 ms，使用模型 `deepseek-v4-flash`。
- 越界问题“FastAPI 如何配置依赖注入？”完成 Run `abb12c38`，选取 0 条证据并明确回答“当前官方证据不足”，没有伪造 FastAPI 知识库引用；首 Token 719 ms，总耗时 1,464 ms。
- 页面手动刷新后数据恢复正常，浏览器控制台无错误；旧静默刷新错误不会覆盖成功状态的前端回归由 `adminKnowledgeLoadState.test.ts` 覆盖。

## 资源与回归

验收完成后的容器资源快照：

| 服务 | CPU | 内存 |
|---|---:|---:|
| Server | 0.11% | 485.6 MiB / 2 GiB |
| Worker | 0.07% | 484.6 MiB / 2 GiB |
| PostgreSQL | 0.01% | 178.1 MiB / 768 MiB |
| Ollama | 0.00% | 429.6 MiB / 2 GiB |
| Web | 0.00% | 6.9 MiB / 128 MiB |
| Caddy | 0.00% | 28.2 MiB / 128 MiB |

提交前回归结果：

- Maven 全模块 `test`：117 个测试通过，其中 PostgreSQL/pgvector 数据库门禁 7 个全部执行；
- Web ESLint：通过；
- Web Vitest：8 个测试文件、20 个测试通过；
- Web 生产构建：通过；
- GitHub Actions 后端、前端和三个生产镜像构建：通过。

## 关闭判定

阶段 A“关闭当前 P1.4 生产 RAG”的八项工作全部完成。后续工作转入可配置项目/知识源、更多数据源、50～100 题评测集、专用 Reranker、监控告警和 GitHub Actions 生产部署密钥修复，不再阻塞 P1.4。
