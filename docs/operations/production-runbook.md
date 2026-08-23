# InsightOps 生产运维手册

以下命令均在服务器仓库目录 `/opt/insightops-agent` 执行。

## 状态与日志

```bash
docker compose --env-file .env.prod -f infra/compose.prod.yml ps
docker compose --env-file .env.prod -f infra/compose.prod.yml logs --tail=200 server
docker compose --env-file .env.prod -f infra/compose.prod.yml logs --tail=200 worker
docker compose --env-file .env.prod -f infra/compose.prod.yml logs --tail=200 caddy
```

不要把含环境变量的 `docker inspect` 完整输出粘贴到公开 Issue。

## 部署与回滚

```bash
bash scripts/deploy-prod.sh 完整Git提交SHA
```

脚本流程：生产预检 -> 当前数据库备份 -> 拉取镜像 -> 启动 -> 检查 PostgreSQL、Ollama、Server、Worker、Web -> 记录成功标签并把 `IMAGE_TAG` 写回权限为 `600` 的 `.env.prod`。持久化只在全部健康检查通过后发生，保证后续手工 Compose 命令继续使用已验收的不可变 SHA，而不会退回本地陈旧的 `latest`。健康检查失败且已有成功标签时自动回滚。

不要手工把生产 `IMAGE_TAG` 改回 `latest`。首次部署或历史环境尚未持久化 SHA 时，先执行一次 `deploy-prod.sh`，再运行其他 Compose 运维命令。

紧急手工回滚：

```bash
bash scripts/deploy-prod.sh 上一个成功的完整Git提交SHA
```

## 备份

```bash
bash scripts/backup-prod.sh
ls -lh backups/
```

脚本会短暂停止 Server/Worker，并生成同一 UTC 时间戳的数据库、上传卷和校验清单：

```text
backups/insightops-<UTC时间>.dump
backups/insightops-<UTC时间>.uploads.tar.gz
backups/insightops-<UTC时间>.sha256
```

三件套必须一起保留；数据库和上传卷任何一项缺失都不是可恢复备份。可在 `backups/` 内执行 `sha256sum --check insightops-<UTC时间>.sha256` 验证。默认保留 30 天。建议每天低峰期运行，并把三件套的加密副本同步到服务器之外。

Cron 示例：

```cron
17 3 * * * cd /opt/insightops-agent && /usr/bin/flock -n /tmp/insightops-backup.lock /usr/bin/bash scripts/backup-prod.sh >> backups/backup-cron.log 2>&1
```

## 恢复

恢复会覆盖当前数据库内容，必须先停业务并确认文件：

```bash
bash scripts/restore-prod.sh backups/insightops-YYYYMMDDTHHMMSSZ.dump --confirm-destructive-restore
```

脚本先验证数据库、同时间戳上传归档和 SHA-256 清单，再停止 Server/Worker、恢复数据库与上传卷并重启。恢复结束后必须测试登录、会话、上传文件下载、RAG 查询和用户隔离。不要在生产数据库上做演练；恢复演练应使用隔离的数据库和卷。

## 监控与告警

Prometheus/Grafana 使用 `observability` profile，并仅绑定服务器回环地址。首次启用前在 `.env.prod` 配置强随机 `GRAFANA_ADMIN_PASSWORD`：

```bash
docker compose --profile observability --env-file .env.prod -f infra/compose.prod.yml up -d prometheus grafana
docker compose --profile observability --env-file .env.prod -f infra/compose.prod.yml ps prometheus grafana
curl --fail --silent http://127.0.0.1:9090/-/ready
curl --fail --silent http://127.0.0.1:3000/api/health
```

这些命令依赖 `.env.prod` 中由成功部署持久化的 `IMAGE_TAG`。若 Compose 提示要重建 Server/Worker，应先停止操作并核对该值，不要用 `latest` 继续。

如需从本地访问，使用 SSH 隧道，不要开放公网端口：

```bash
ssh -L 9090:127.0.0.1:9090 -L 3000:127.0.0.1:3000 insightops
```

关键规则位于 `infra/monitoring/alerts.yml`，覆盖 Server/Worker 不可用、知识采集租约过期、Embedding 积压、上传处理失败和 HTTP 5xx 比例。Prometheus 当前只负责评估规则；要把告警发送到邮件或团队渠道，仍需另行接入 Alertmanager。

## 证书

Caddy 自动申请和续期证书。证书状态异常时：

```bash
docker compose --env-file .env.prod -f infra/compose.prod.yml logs --tail=200 caddy
```

重点检查 DNS 是否仍指向本机、80/443 是否可达、`APP_ADDRESS` 是否只有真实域名且没有 `http://`。不要删除 `caddy-data` 卷，否则会丢失现有证书状态并触发重新申请。

## 磁盘与资源

```bash
df -h
docker system df
docker stats --no-stream
```

不要用 `docker compose down -v`，`-v` 会删除 PostgreSQL、Ollama、Grafana 和 Caddy 数据卷。清理旧镜像前先确认当前和上一成功标签仍然存在。

## 故障优先级

1. 数据库不可用：停止发布，检查磁盘、PostgreSQL 日志和最近备份。
2. Caddy/HTTPS 不可用：检查 DNS、安全组和证书日志。
3. Server 不健康：查看 Flyway、数据库连接和配置错误。
4. Worker 不健康：聊天仍可能可用，暂停采集并检查任务日志。
5. Ollama 不可用：RAG 检索会降级；检查内存、模型卷和 `ollama list`。
6. DeepSeek 不可用：保留已采集证据与执行记录，检查额度和 Provider 状态，不循环重试消耗预算。

## P2.3-C 普通聊天接管演练

该演练会向生产 Server 容器发送一次 `SIGKILL`，可能造成短暂 5xx；它不停止 PostgreSQL、不删除卷，也不会自动选择或创建 Run。只应在低峰期对明确的非关键测试 Run 执行，并提前确认 Grafana 和日志可观察。

演练前必须保证聊天租约短于 Run 总时限；当前生产默认值为 30 秒租约、5 秒心跳和 90 秒总时限。脚本会在任何破坏动作前拒绝不满足该关系的配置。

先在管理端确认目标 Run 正在执行且至少产生一个安全点，再在服务器执行：

```bash
cd /opt/insightops-agent
bash scripts/p2-3-chat-takeover-drill.sh \
  目标AgentRun完整UUID --confirm-production-restart
```

脚本会在终止进程前验证 Run、可用安全点和成本预留；随后验证：

1. Server 恢复健康。
2. 租约过期后领取次数增加、Worker 身份改变。
3. 新增 `run_recovered`。
4. Run 到达可计费终态。
5. `agent_cost_ledger` 只有一条 `SETTLE` 或 `RELEASE`。

正式关闭 P2.3-C 时，优先从 GitHub Actions 手动运行
`P2.3-C production takeover drill`，并在 `confirmation` 输入
`P2.3-C-TAKEOVER-DRILL`。工作流复用 production Environment 的部署 SSH
凭据，在服务器内部完成以下动作，任何前置失败都发生在 `SIGKILL` 之前：

1. 核对当前租约短于 Run 总时限。
2. 使用 `.env.prod` 的现有管理员账号登录，但不输出账号密码或 Cookie。
3. 创建带唯一 `P23-TAKEOVER-<workflow-run>-<attempt>` 标记的非关键测试 Run。
4. 只在该 Run 已持有租约、存在可用安全点并只有一条成本预留时调用演练脚本。
5. 由演练脚本验证 Server 恢复、跨实例接管、`run_recovered` 和单次成本终态。

自动验收脚本不会删除测试会话，确保生产审计链可回看。

若 `.env.prod` 不再保留初始化管理员密码，工作流不会改密码、创建临时账号或绕过认证；
它会输出唯一标记并等待最多 5 分钟。此时由已登录用户在研究问答页面发送包含该标记的
非关键测试问题。只有该问题对应的 Run 满足全部安全前置条件时，工作流才继续执行
故障注入。

任何前置条件不满足都会在 `SIGKILL` 前退出。若脚本在终止后意外中断，退出 Trap 会尝试重新拉起 Server；仍须立即执行：

```bash
docker compose --env-file .env.prod -f infra/compose.prod.yml up -d server
docker compose --env-file .env.prod -f infra/compose.prod.yml ps server
docker compose --env-file .env.prod -f infra/compose.prod.yml logs --tail=200 server
```

不要移除强确认参数，不要把该脚本放入定时任务，也不要对真实用户的重要 Run 演练。
