# Stage 3 生产可靠性验收记录（2026-08-24）

## 1. 验收范围

- 10 个固定 Java/AI 项目在生产 PostgreSQL 的 72 小时采集历史逐项目通过稳定性门禁。
- Prometheus 的 18 条规则接入 Alertmanager；唯一 Canary 经已批准的随机私密 ntfy 主题完成端到端到达验证。
- 数据库与上传文件生成 AES-256 加密异地包，只把密文、密文 SHA-256 和非敏感元数据保存为 GitHub Actions Artifact 30 天。
- 删除 Runner 首份传输副本后，从 Artifact 重新下载、校验、回传，并在独立 PostgreSQL 18 容器完成隔离恢复。
- 外部主题、GitHub Token、备份口令、明文数据库和用户文件均未写入仓库、Artifact 元数据或本验收记录。

## 2. 自动化与发布结果

| 门禁 | 结果 |
|---|---|
| 后端全仓真实数据库 `mvn verify` | PASS：283/283，Failures 0，Errors 0，Skipped 0 |
| PostgreSQL / Flyway | PASS：PostgreSQL 18.4，Flyway 37/37 |
| 前端 Vitest | PASS：23 文件，63/63 |
| 前端静态与生产构建 | PASS：ESLint、`vue-tsc`、Vite build |
| 监控配置 | PASS：Prometheus 配置有效、18 条规则有效、Alertmanager 配置有效 |
| 生产 Compose 与 Shell | PASS：Compose 展开、关键脚本语法和合同测试全部通过 |
| 凭据注入边界 | PASS：只经 SSH 标准输入写入权限 `600` 的生产环境文件，仅重建 Worker，日志不输出 Token |

- 可靠性实现提交：`d6bb1aaf9aef20cc917e995e86ff71b4284a25d8`。
- 实现提交 CI Run `32737419375`：backend、frontend、server image、worker image、web image 全部成功。
- 实现提交 Deploy Run `32737888195`：成功。
- 生产 GitHub 凭据安全注入修复提交：`2e3e59fa4e5c3c18d573de8c30bfc483a6c76b13`。
- 修复提交 CI Run `32739451313`：五个 Job 全部成功。
- 修复提交 Deploy Run `32739861289`：成功，生产运行精确 SHA `2e3e59fa...`。

## 3. 10 项目 72 小时稳定性门禁

生产验收 Run `32741197128` 以每项目成功率不低于 95%、样本数不低于理论调度次数的 80% 为门槛。10 个项目均为 `PRESENT`、已启用、当前状态 `SUCCEEDED`、连续失败 0：

| 项目 | 周期（小时） | 72 小时终态样本 | 成功/失败 | 成功率 |
|---|---:|---:|---:|---:|
| spring-projects/spring-ai | 6 | 12 | 12 / 0 | 100% |
| langchain4j/langchain4j | 6 | 12 | 12 / 0 | 100% |
| langgenius/dify | 6 | 12 | 12 / 0 | 100% |
| alibaba/spring-ai-alibaba | 12 | 6 | 6 / 0 | 100% |
| quarkiverse/quarkus-langchain4j | 12 | 6 | 6 / 0 | 100% |
| modelcontextprotocol/java-sdk | 12 | 6 | 6 / 0 | 100% |
| openai/openai-java | 12 | 6 | 6 / 0 | 100% |
| anthropics/anthropic-sdk-java | 24 | 3 | 3 / 0 | 100% |
| googleapis/java-genai | 24 | 3 | 3 / 0 | 100% |
| ollama/ollama | 24 | 3 | 3 / 0 | 100% |

门禁输出：`10-project stability gate passed for 72h at >=95% success`。

## 4. 外部告警到达

- Alertmanager 只绑定生产服务器回环地址，Prometheus 通过内部网络发送规则事件。
- 验收发送不含业务或用户内容的唯一合成 Canary。
- Run `32741197128` 从同一随机私密 ntfy 主题轮询到唯一标记，输出 `ALERT_CANARY=PASS`。
- 验收结束后发送 resolved 事件；主题 URL 和随机主题值未进入 Actions 日志或本文档。

## 5. 加密 Artifact 与异地往返

- Artifact ID：`9525272938`。
- Artifact 名称：`insightops-stage3-32741197128-1`。
- 密文三件套总大小：47,705,646 bytes。
- 创建时间：2026-08-24 14:52:29 UTC。
- 到期时间：2026-09-23 14:52:27 UTC，保留期 30 天。
- 加密参数：AES-256-CBC、PBKDF2-HMAC-SHA-256、200,000 次迭代、每包随机 Salt。
- 口令只保存在生产服务器权限受限的 Secret 文件中；Artifact 不含口令、`.env.prod` 或任何明文备份。

工作流在 Artifact 上传后删除 Runner 首份传输副本，再通过 `download-artifact` 获取异地副本、验证密文 SHA-256，并回传到限定的 `recovery-imports/<Run 标记>/` 边界。

## 6. 隔离恢复结果

从 Artifact 往返副本恢复得到：

- `ISOLATED_RECOVERY=PASS`；
- Flyway 版本 `37`；
- 固定项目 `10`；
- Workspace `1`；
- 用户 `1`；
- 当前生产上传记录 `0`，上传归档仍包含在备份格式中，逐文件摘要验证循环无缺失项。

恢复目标是临时命名的 `pgvector/pg18` 容器。脚本未连接生产 Compose、生产 PostgreSQL 数据卷或上传卷；退出时删除临时容器和工作目录。最终输出 `STAGE3_PRODUCTION_RELIABILITY=PASS`。

## 7. 失败审计与修复

首次验收 Run `32738186098` 在发送 Canary 和上传 Artifact 前安全失败，因为生产 `.env.prod` 没有长期 GitHub 只读凭据。该失败未产生外部告警或异地副本。随后增加受保护 `production` Environment Secret 到生产 Worker 的标准输入注入链，避免匿名 GitHub API 限额制造伪稳定；替代 Run `32741197128` 全部通过。

## 8. 生产 Smoke 与结论

- `https://insightops.canghaior.com/healthz`：HTTP 200。
- `https://insightops.canghaior.com/admin/agent-workflows`：HTTP 200。
- 验收 Run `32741197128`：所有 11 个 Job 步骤成功。

10 项目长期稳定性、Alertmanager 外部送达、AES-256 加密异地 Artifact 和隔离恢复四条生产证据链全部关闭。Stage 3 完成，可以进入公开 SaaS Go/No-Go 评估。
