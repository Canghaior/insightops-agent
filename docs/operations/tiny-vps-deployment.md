# 1 核 / 2 GB VPS 轻量部署

此配置起初只运行 PostgreSQL、Server、Web、Caddy。可在登录和基础聊天稳定后启用有资源上限的 Worker、少量采集及 PostgreSQL 关键词检索问答；本机 Ollama、向量嵌入和监控栈保持关闭。关键词模式没有中文语义检索能力，不能等同于原生产环境的混合向量 RAG。公开注册默认关闭。

VPS 上仍有 WireGuard 和代理，只有 Caddy 发布 80/443；数据库 5432 和应用 18080 不对公网开放。首次部署前检查 `free -h`、`df -h /` 和 `ss -lntup`。20 GB 系统盘需要定期查看余量。

## 初始化

在 Ubuntu 24.04 VPS 上，以普通 sudo 用户登录。以下示例工作目录为 `/home/vpsadmin/insightops-agent`：

```bash
git clone https://github.com/Canghaior/insightops-agent.git ~/insightops-agent
cd ~/insightops-agent
python3 scripts/init-tiny-env.py
sudo bash scripts/install-docker-tiny.sh
```

`.env.tiny` 由初始化脚本生成随机数据库和管理员临时密码，权限为 600，不应提交到 GitHub。Docker 安装脚本先备份现有防火墙规则，不把用户加入具有 root 等效权限的 `docker` 组。之后的 Docker 命令需使用 `sudo`。

如果已发布的 GHCR `latest` Server 镜像尚未包含关闭 DeepSeek 时的启动修复，需构建本地修复镜像。在有 Java 21、Maven 的开发机上构建：

```bash
mvn -pl insightops-server -am -DskipTests package
```

把 `insightops-server/target/insightops-server-0.1.0-SNAPSHOT.jar` 复制为 VPS 仓库下的 `.tiny-server-build/app.jar`，并把 `infra/tiny-server-image/Dockerfile` 复制为同目录的 `Dockerfile`。然后在 VPS 运行：

```bash
cd ~/insightops-agent
sudo bash scripts/install-tiny-server-image.sh
```

它从已发布的 Server 镜像构建本地修复镜像，启动 PostgreSQL、Web、Server，并等待 Server 健康检查通过。此构建目录被 Git 忽略；不要把程序包或 `.env.tiny` 提交到仓库。若以后 GitHub 发布的 Server 镜像已经包含此修复，可直接使用 GHCR 镜像，并在 `.env.tiny` 中去掉 `TINY_SERVER_IMAGE` 覆盖。

## 域名与 HTTPS

在 DNSPod 中，将 `insightops.canghaior.com` 的现有 A 记录指向 VPS 公网 IPv4。保留域名的 NS、MX、TXT 等记录。然后在 VPS 运行：

```bash
sudo bash ~/insightops-agent/scripts/start-tiny-public.sh
```

该脚本在 UFW 放行 80/443，拉取并启动 Caddy。验证域名解析、首页和 API：

```bash
curl -I https://insightops.canghaior.com/
curl https://insightops.canghaior.com/api/v1/system/status
sudo docker compose --env-file ~/insightops-agent/.env.tiny \
  -f ~/insightops-agent/infra/compose.tiny.yml ps
```

DNS 缓存可能在原 TTL 到期后才更新。HTTPS 证书签发需域名已指向新 VPS，且公网 80/443 可访问。

## 首次登录与模型

打开 `https://insightops.canghaior.com/login`，用户名为 `alpha-owner`。只在自己的 SSH 终端查看临时密码：

```bash
grep '^AUTH_BOOTSTRAP_PASSWORD=' ~/insightops-agent/.env.tiny
```

不要把密码粘贴到聊天或截图。首次登录按页面提示修改密码。确认新密码能登录后，运行 `python3 scripts/finalize-tiny-owner.py`，它会关闭 bootstrap 并从 `.env.tiny` 清除临时密码。随后用 `sudo docker compose --env-file .env.tiny -f infra/compose.tiny.yml up -d server` 重新创建 Server 容器。

没有 DeepSeek API Key 时，站点和账号可用，但聊天不可用。有 Key 时，在自己的 SSH 终端运行 `bash scripts/configure-tiny-deepseek.sh`，按提示隐式输入 Key。脚本会验证 Key、设置当前 Flash 模型和保守的高峰价格估算、重启 Server；不会将 Key 打印到终端。

## 低流量演示：采集与关键词问答

确认基础聊天可用后，在 VPS 上运行：

```bash
cd ~/insightops-agent
sudo bash scripts/enable-tiny-demo.sh
```

脚本先验证 Caddy 配置，再启动内存上限 512 MB、Java 堆上限 256 MB 的 Worker；Worker 健康且剩余内存足够时，开启 Server 关键词检索问答并发布 `/about` 项目介绍页。采集限制为单次一个项目、每个官方文档源最多 20 页；用户上传单个文件最多 5 MB。若 Worker 启动失败，脚本会停止 Worker，保留原网站。官方文档首次采集仍需管理员在“知识库采集”中手动触发。可先选一个文档源验证，不要一次启动所有来源。上传的文件需先完成解析，才会被关键词检索命中。

演示问题请包含明确的项目名和技术术语，例如“Spring AI 的 ChatClient 如何使用？”。关键词模式对中文同义表达和模糊问题命中较弱，结果页显示 `KEYWORD` 属于预期行为；要恢复向量语义检索需外部 Embedding 服务或更大机器。若内存紧张，先运行 `sudo docker compose --env-file .env.tiny -f infra/compose.tiny.yml stop worker`，基础登录和聊天可继续使用。

`https://insightops.canghaior.com/about` 是可公开索引的项目介绍页，`robots.txt` 和 `sitemap.xml` 指向它；搜索引擎收录时间不受本站控制。访客进入应用仍需要账号。公开注册依赖可用的邮件发送、Turnstile、运营者信息和真实注册链路验收；不要只切换 `PUBLIC_BETA_ENABLED`。面试演示可由管理员为少量试用者分别创建账号。

## 日常维护

使用 `sudo docker stats --no-stream`、`free -h`、`df -h /` 观察资源。备份 PostgreSQL、上传文件和恢复所需密钥，并把副本保存到 VPS 之外。原 `deploy-prod.sh`、`backup-prod.sh`、`restore-prod.sh` 只适用于完整生产编排，不能直接用于此轻量版。
