# 生产可靠性门禁：10 项目、Alertmanager 与异地恢复

## 1. 目标与边界

本阶段关闭三条独立证据链：10 个种子项目的长期采集稳定性、Prometheus 告警经 Alertmanager 到达已批准的外部接收端，以及数据库与用户上传文件的加密异地副本可在隔离环境恢复。

外部接收端和异地存储位置必须由所有者显式批准。所有者已批准使用随机私密 ntfy 主题接收纯运行状态，并批准将 AES-256 加密副本在 GitHub Actions Artifact 保留 30 天。仓库不内置具体主题、访问令牌或备份密钥；异地备份密钥只在生产服务器生成和保存，不随备份传输。

## 2. 10 项目长期稳定性

`scripts/production-stability-report.sh` 使用生产 PostgreSQL 中不可变的 `job_task` 历史，而不是仅查看页面当前状态。默认门禁为 72 小时、每项目成功率不低于 95%，并要求：

- 固定的 10 个种子仓库全部存在且启用；
- 生产配置存在 `GITHUB_TOKEN`，避免匿名限额形成伪稳定；
- 当前状态只能是 `SUCCEEDED` 或持有有效租约的 `RUNNING`；
- 连续失败数为 0；
- 最近同步时间不超过配置周期加 6 小时宽限；
- 时间窗内终态样本不少于理论调度次数的 80%，且至少 2 次；
- 每个项目单独满足成功率，不允许用高频项目掩盖低频项目失败。

Server 新增 `insightops_project_collection_failed` 与 `insightops_project_collection_stale` Gauge；对应 `ProjectCollectionFailures` 和 `ProjectCollectionStale` 规则进入现有告警组。

## 3. Alertmanager 外发

生产 Compose 增加固定版本 `prom/alertmanager:v0.33.1`，仅绑定 `127.0.0.1:9093`。Prometheus 通过内部网络的 `alertmanager:9093` 发送 18 条规则；Alertmanager 用 `/run/insightops-secrets/alertmanager-webhook-url` 读取接收 URL，避免 URL 出现在 Git 历史或容器参数中。

`scripts/ensure-prod-reliability-secrets.sh` 首次执行时生成 192-bit 随机主题并固定为 `https://ntfy.sh/insightops-<随机值>?template=alertmanager&firebase=no`，同时写入权限受限的生产环境文件和容器只读 Secret；主题值不会写入日志、GitHub 输出或验收文档。`scripts/configure-prod-reliability.sh` 只重建 observability 服务，不重启 PostgreSQL、Server、Worker 或 Web，并验证 Alertmanager/Prometheus readiness 以及 Prometheus 已发现 Alertmanager。

生产验收发送带唯一 Run 标记、且不含业务或用户内容的 Canary，再通过 ntfy JSON polling API 从同一私密主题取得该标记；仅检查 HTTP 200 或本机日志不能关闭“外发到达”门禁。ntfy 发布关闭 Firebase 分发，主题依赖高熵随机值隔离。

## 4. 加密异地备份

`scripts/create-offsite-backup.sh` 先调用现有一致性备份，得到数据库、上传卷和 SHA-256 三件套，再创建可移植清单并使用：

- AES-256-CBC；
- PBKDF2-HMAC-SHA-256；
- 200,000 次迭代；
- 每包随机 Salt；
- 生产服务器本地 256-bit 随机口令。

输出为 `.tar.gz.enc`、外层 `.sha256` 和不含密钥的 `.metadata`。工作流只将这三项上传到 GitHub Actions Artifact，设置 `retention-days: 30`；不上传口令、`.env.prod`、明文数据库或明文用户文件。

## 5. 隔离恢复演练

`scripts/restore-offsite-drill.sh` 只接受 `recovery-imports/` 内、从已批准异地位置重新取回的包，并要求 `--confirm-isolated-recovery`。脚本执行以下门禁：

1. 校验外层密文 SHA-256、格式元数据和归档路径安全；
2. 解密后校验数据库与上传归档的可移植清单；
3. 启动独立命名的临时 `pgvector/pg18` 容器，不连接生产 Compose 或生产卷；
4. `pg_restore --exit-on-error` 恢复数据库；
5. 验证 Flyway 版本、10 个种子项目、Workspace 和用户；
6. 对每条 `knowledge_upload.storage_key` 验证隔离目录中的文件存在且 SHA-256 与数据库一致；
7. Trap 删除临时容器和 `/tmp` 工作目录。

脚本不引用 `postgres-data`、`knowledge-uploads`、生产 Compose 或 `docker compose down -v`。生产数据库和上传卷在演练中始终只读且不作为恢复目标。

10 项目长期门禁要求生产 Worker 使用长期只读 GitHub 凭据，避免匿名 API 限额制造伪稳定。凭据由 `production` Environment 的 `PRODUCTION_GITHUB_TOKEN` Secret 提供；工作流只通过 SSH 标准输入交给 `scripts/configure-prod-github-token.sh`。脚本校验单行格式、原子写入权限为 `600` 的 `.env.prod`，清空进程变量并仅重建 Worker；工作流和验收文档均不输出 Token。

## 6. 自动化与生产关闭条件

- Shell 语法、生产 Compose、Prometheus `promtool`、Alertmanager `amtool` 全部通过；
- 后端全仓真实 PostgreSQL 门禁和前端全套门禁通过；
- CI 与不可变镜像部署成功；
- 72 小时 10 项目报告逐项目通过；
- 外部 Canary 在已批准接收端可审计到达；
- 加密包离开生产服务器并在异地保留；
- 从该异地副本重新取回并完成隔离恢复，记录 Artifact/对象版本、密文 SHA-256 和恢复不变量；
- 验收文档不得记录外部 URL、主题名、访问令牌、备份口令或用户文件内容。

`.github/workflows/stage3-production-reliability.yml` 使用受保护的 `production` Environment 和强确认词串联上述门禁。它先把加密三件套传离生产机并上传 Artifact，删除 Runner 首份拷贝，再用 `download-artifact` 取得该 Artifact 副本，校验密文 SHA-256 后传回 `recovery-imports/<Run 标记>/`，最后调用隔离恢复脚本。该顺序证明恢复输入来自异地往返副本，而不是生产机原始备份。
