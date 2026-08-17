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

脚本流程：生产预检 -> 当前数据库备份 -> 拉取镜像 -> 启动 -> 检查 PostgreSQL、Ollama、Server、Worker、Web -> 记录成功标签。健康检查失败且已有成功标签时自动回滚。

紧急手工回滚：

```bash
bash scripts/deploy-prod.sh 上一个成功的完整Git提交SHA
```

## 备份

```bash
bash scripts/backup-prod.sh
ls -lh backups/
```

备份文件为 `backups/insightops-<UTC时间>.dump`，旁边带 SHA-256 校验文件。默认保留 30 天。建议每天低峰期运行，并把加密副本同步到服务器之外。

Cron 示例：

```cron
17 3 * * * cd /opt/insightops-agent && /usr/bin/bash scripts/backup-prod.sh >> /var/log/insightops-backup.log 2>&1
```

## 恢复

恢复会覆盖当前数据库内容，必须先停业务并确认文件：

```bash
bash scripts/restore-prod.sh backups/insightops-YYYYMMDDTHHMMSSZ.dump --confirm-destructive-restore
```

脚本先验证路径和 SHA-256，再停止 Server/Worker、恢复数据库并重启。恢复结束后必须测试登录、会话、RAG 查询和用户隔离。

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
