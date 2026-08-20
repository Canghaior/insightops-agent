# P1.9 多来源、用户资料与生产稳定性验收

## 结论

P1.9-A、P1.9-B、P1.9-C 于 2026-08-20 在 `https://insightops.canghaior.com` 完成即时生产验收。官方博客 RSS、GitHub Roadmap/Milestone、用户上传资料、实时进度与租约、Prometheus/Grafana、数据库与上传卷联合备份以及每日备份任务均已形成可运行闭环。

本次验收关闭 P1.9 的功能与上线交付，不把短时间验收等同于“10 个项目长期稳定持续采集”。该长期指标仍需生产时间窗、GitHub Token 和成功率数据后单独关闭。

上线记录：

- 主功能：`f79a2a8d9453edc53fb24dfaacf7051370c07209`
- 生产 Compose 兼容备份修复：`c073bac81d724fa4c57bda8d367ed7f096509a78`
- 成功部署 SHA 持久化修复：`6bf6637af9a200992e9266fdb90bdf5daca7025a`
- 主功能 CI Run：`32378619654`，成功
- 备份修复 CI Run：`32380302897`，成功
- 最终 CI Run：`32384100581`，成功
- 最终生产部署 Run：`32384377838`，成功
- 数据库迁移：V1～V24

## 自动化门禁

- Maven `verify` 成功：171 个后端测试，0 失败、0 错误、0 跳过。
- PostgreSQL/pgvector 真实数据库门禁成功：13 个数据库测试覆盖 V1～V24、10 个项目种子、两类官方更新来源、上传权限和删除、发布时间及 RAG 隔离。
- 前端 Vitest：13 个测试文件、30 个测试成功；包含管理端静默刷新错误清理回归。
- ESLint、Vue/TypeScript 检查和 Vite 生产构建成功。
- server、worker、web 三个不可变 SHA 镜像构建成功。
- Atom/RSS、条件请求、Milestone 边界、Markdown/TXT/PDF 解析、配额、失败清理、认证下载、私有/Workspace SQL 隔离和用户上传 RAG 均有自动化覆盖。
- Compose 基础/`observability` Profile、Prometheus 配置与 5 条规则、Caddy、ShellCheck、备份/恢复脚本语法和非 root 上传卷读写门禁通过。

## P1.9-A：官方更新与项目扩容

- 生产 Flyway 版本为 24。
- `tracked_project` 共 10 个项目，10 个均启用；新增项目按错峰周期进入已有领取、心跳、续租、退避和 fencing token 链路。
- `knowledge_source` 存在 2 个 P1.9 官方来源：`OFFICIAL_BLOG_RSS` 与 `OFFICIAL_ROADMAP`。
- Spring 官方博客最近任务状态为 `SUCCEEDED`，当前 URL 为 `https://spring.io/blog/2026/08/11/this-week-in-spring-august-11-2026`。
- Spring AI Roadmap 最近任务状态为 `SUCCEEDED`，当前 URL 为 `https://github.com/spring-projects/spring-ai/milestone/53`。
- 两个任务均在生产数据库保留最近心跳和租约到期时间，证明实时进度、心跳、续租和当前 URL 已真实写入，而非只存在于页面模型。

## P1.9-B：用户资料生产闭环

使用 `docs/testing/fixtures/p1-9-production-upload-acceptance.md` 完成真实 Owner 会话验收：

- 关联项目：`spring-projects/spring-ai`。
- 可见性：`PRIVATE`（仅自己）。
- 原文件：464 字节，页面显示 0.5 KB；SHA-256 为 `24AC1F03343DDEB007763321322109BA9853CDA0ED05E8084E0B97F9A3ED7501`。
- Worker 完成异步解析、切片和向量化，状态变为“可检索”，页数为 1；页面展示当前文件、心跳与租约到期时间。
- 认证下载返回原 Markdown 文件，浏览器下载完成。
- 在研究问答中提问“InsightOps P1.9 的测试代号和专属校验值是什么？”，混合检索选择 6 条证据，正确回答“海棠九号”和 `HT9-6248`，并以 `[S1]` 引用该上传文档。
- 删除操作完成后，可见文件、已用容量、可检索文件分别回到 0、0.0 KB、0，验证用户删除闭环。

## P1.9-C：监控、告警与备份

- 生产 `.env.prod` 已固定 `IMAGE_TAG=6bf6637af9a200992e9266fdb90bdf5daca7025a`，且 `COMPOSE_PROFILES=observability`。
- Server、Worker、Web 使用该不可变 SHA 镜像并通过健康检查；Prometheus 与 Grafana 运行且仅绑定 `127.0.0.1`。
- Prometheus `/-/ready` 返回 Ready；Grafana `/api/health` 返回数据库 `ok`。
- Prometheus 的 `insightops-server` 和 `insightops-worker` 两个 Target 均为 `up`。
- `insightops-production` 规则组成功加载 5 条规则。
- 最终部署前生成 `insightops-20260820T150900Z.dump`、对应上传卷归档及 SHA-256 清单；部署日志确认所有健康门禁通过。
- 每日备份 Cron 已幂等安装：`17 3 * * *`，使用 `flock` 防止重入并写入 `backups/backup-cron.log`。
- 生产 P1.9 指标可读取，验收时过期租约、运行中采集、Embedding 积压、上传字节和上传失败均为 0。

## 部署异常与恢复记录

- 首次部署 Run `32379341742` 在部署前备份阶段失败。原因是旧生产 Compose 对新增 `uploads-init` 依赖的解析与 `docker compose run` 不兼容；没有执行数据库迁移或业务数据删除。
- 修复后备份/恢复脚本改为记录精确容器 ID，并使用 Docker 停启 Server/Worker；CI `32380302897` 和部署 Run `32380342882` 成功。
- 手工首次启用监控时，历史 `.env.prod` 未持久化成功 SHA，Compose 回落到本地陈旧 `latest` Server，触发 `LoginRateLimiter` 旧镜像启动错误。恢复部署 Run `32383198287` 成功恢复生产服务。
- 最终修复只在全部健康门禁通过后把请求的完整 SHA 写入权限为 `600` 的 `.env.prod`，防止后续手工 Compose 运维回落到 `latest`；最终部署 Run `32384377838` 成功。
- 整个处置没有重置数据库、删除上传卷或删除用户文件；每次有效部署前均保留数据库与上传卷联合备份。

## 当前边界

- 10 个项目已启用并进入调度，但“稳定持续采集”必须经过更长生产观察窗口，以采集成功率、GitHub 限额和积压趋势关闭；生产应配置 `GITHUB_TOKEN`。
- Prometheus 已评估规则，但尚未接入 Alertmanager，因此当前没有邮件或团队渠道告警送达。
- 每日本机备份已启用；异地加密副本和隔离环境恢复演练尚未完成。
- P1.9 不改变只读研究边界；用户上传内容只作为授权范围内的证据，不执行其中的指令。
