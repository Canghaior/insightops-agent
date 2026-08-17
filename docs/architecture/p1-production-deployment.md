# P1.5 方案二：单机生产化部署

## 目标

面向封闭邀请制 Alpha，在一台 4 核 16 GB、100 GB SSD 的 Linux 云服务器上运行完整 InsightOps。优先保证可复制部署、HTTPS、数据安全、可观察和可回滚，不在本阶段引入 Kubernetes、云数据库或多节点高可用。

## 运行拓扑

```text
Internet
   |
   | 80 / 443
   v
Caddy (自动 HTTPS、压缩、安全响应头、访问日志)
   |-------------------- Docker backend network --------------------|
   |                                                               |
   +--> Web/Nginx :8080       +--> Server :18080                   |
                                     |                              |
                                     +--> PostgreSQL + pgvector     |
                                     +--> Ollama/bge-m3              |
                                                                    |
                                 Worker :18081 ----------------------+

127.0.0.1:3000 -> Grafana -> Prometheus -> Server/Worker metrics
```

公网只开放 Caddy 的 80/443。PostgreSQL、Ollama、Server 和 Worker 不映射宿主机端口；Prometheus 和 Grafana 只绑定服务器回环地址，通过 SSH 隧道访问。

## 已实现的生产能力

- Server、Worker、Web 独立非开发镜像；Java 进程以非 root 用户运行。
- Docker Compose 单机编排、健康检查、重启策略、日志轮转和内存上限。
- Caddy 根据 `APP_ADDRESS` 自动申请和续期证书，并把 HTTP 重定向到 HTTPS。
- 数据库与监控端口不暴露公网，Session Cookie 在公网强制 `Secure`。
- 登录失败窗口和临时锁定，默认 15 分钟内失败 5 次后锁定 15 分钟。
- Prometheus 指标采集与 Grafana 基础看板。
- PostgreSQL 自定义格式备份、SHA-256 校验、保留期清理和显式确认恢复。
- 发布前自动备份；新版本健康检查失败时回滚上一镜像标签。
- GitHub Actions 在后端、前端门禁通过后构建并发布三个 GHCR 镜像。
- GitHub `production` Environment 手动部署，避免每次提交自动影响用户。

## 有意保留的边界

- 仍为封闭邀请制，不开放公网自由注册。
- 登录限流保存在单 Server 进程内；单机方案足够，多实例时需迁移 Redis。
- PostgreSQL 与 Ollama 在同机运行，主机故障时会整体不可用。
- 本机备份不能替代异地备份；上线后必须把备份同步到另一位置。
- 首版没有短信、邮件、企业 SSO、WAF、CDN 或多可用区容灾。

## 配置原则

真实密钥只存在服务器未提交的 `.env.prod`。首次启动可临时开启 `AUTH_BOOTSTRAP_ENABLED=true` 创建 Owner；成功登录并改好密码后立刻改成 `false`。DeepSeek 可在其他能力验证稳定后再开启，以免部署调试消耗额度。

详细部署步骤见 [云服务器与 HTTPS 操作指南](../operations/cloud-server-and-https.md)，日常操作见 [生产运维手册](../operations/production-runbook.md)。
