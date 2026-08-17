# 云服务器、域名与 HTTPS 操作指南

本文对应 P1.5 方案二。购买和实名认证必须由仓库所有者本人完成；代码侧已经准备好部署产物。

## 1. 需要购买什么

必须项：

1. 一台 Linux 云服务器：建议 4 核 CPU、16 GB 内存、100 GB SSD、Ubuntu 24.04 LTS。Ollama `bge-m3` 和 PostgreSQL 与应用同机运行，8 GB 内存余量偏小。
2. 一个域名：例如 `insightops.example.com` 使用主域名的子域名即可，不需要再买独立 SSL 证书。
3. DeepSeek API 余额：仅聊天、情报分析和 RAG 生成消耗；本地 `bge-m3` 向量化不购买新 Key。

不需要购买：

- HTTPS 证书：Caddy 使用公开 ACME CA 自动申请和续期。
- 独立 PostgreSQL、向量数据库、Redis、Kubernetes、CDN、对象存储：封闭 Alpha 暂不需要。

建议在中国大陆以外区域做首轮 Alpha，可减少域名备案对首次上线时间的影响；若使用中国大陆服务器，应先按云厂商要求完成 ICP 备案。具体合规要求以服务器地域和云厂商当前规则为准。

## 2. 创建服务器

在云厂商控制台选择：

- 系统：Ubuntu Server 24.04 LTS x86_64。
- 规格：4 vCPU / 16 GB RAM。
- 系统盘：SSD 100 GB 或以上。
- 登录：优先 SSH 密钥，不使用弱密码。
- 安全组入站：`22/tcp` 只允许你的固定公网 IP；`80/tcp`、`443/tcp`、`443/udp` 允许公网。
- 不开放：5432、11434、18080、18081、3000、9090。

记录公网 IPv4 地址。第一次连接：

```bash
ssh ubuntu@服务器公网IP
```

## 3. 配置域名 DNS

在域名 DNS 控制台新增：

```text
类型  主机记录     值
A     insightops   服务器公网 IPv4
```

最终域名示例为 `insightops.example.com`。等待解析生效后，在本机验证：

```bash
nslookup insightops.example.com
```

解析结果必须是刚才的服务器公网 IP。Cloudflare 代理如已启用，首次验证建议先使用“仅 DNS”，确认 Caddy 证书成功后再决定是否打开代理。

## 4. 安装 Docker 与 Git

登录服务器后，按照 Docker 官方的 Ubuntu 安装文档安装 Docker Engine 和 Compose Plugin，不使用来源不明的一键脚本。安装完成后验证：

```bash
docker version
docker compose version
git --version
```

把当前用户加入 `docker` 组后需要重新登录 SSH 才生效。正式环境也可以保留 `sudo docker ...`，不要为方便而开放 Docker TCP API。

## 5. 下载项目并创建生产配置

```bash
sudo install -d -o "$USER" -g "$USER" /opt/insightops-agent
git clone https://github.com/Canghaior/insightops-agent.git /opt/insightops-agent
cd /opt/insightops-agent
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

生成三个互不相同的随机密码：

```bash
openssl rand -base64 32
openssl rand -base64 32
openssl rand -base64 32
```

编辑 `.env.prod`，至少修改：

```properties
APP_ADDRESS=insightops.example.com
HTTP_PORT=80
HTTPS_PORT=443

POSTGRES_PASSWORD=第一个随机密码
DB_PASSWORD=与POSTGRES_PASSWORD完全相同
GRAFANA_ADMIN_PASSWORD=第二个随机密码

AUTH_BOOTSTRAP_ENABLED=true
AUTH_BOOTSTRAP_USERNAME=alpha-owner
AUTH_BOOTSTRAP_PASSWORD=第三个随机密码
AUTH_SECURE_COOKIE=true

ALLOW_INSECURE_LOCAL=false
```

若现在启用 DeepSeek，再配置：

```properties
DEEPSEEK_API_KEY=你的真实Key
DEEPSEEK_ENABLED=true
SPRING_AI_MODEL_CHAT=openai
```

不要把 `.env.prod` 发给任何人，也不要提交 Git。

## 6. 首次部署

GitHub Actions 尚未产出镜像时，可以在服务器本机构建：

```bash
bash scripts/preflight-prod.sh
docker compose --env-file .env.prod -f infra/compose.prod.yml build server worker web
docker compose --env-file .env.prod -f infra/compose.prod.yml up -d
```

CI 已发布 `latest` 或某个提交 SHA 镜像后，使用：

```bash
bash scripts/deploy-prod.sh latest
```

查看状态：

```bash
docker compose --env-file .env.prod -f infra/compose.prod.yml ps
docker compose --env-file .env.prod -f infra/compose.prod.yml logs --tail=100 caddy server worker
```

第一次需要下载基础镜像和 `bge-m3`，耗时取决于服务器带宽。所有核心容器健康后，访问 `https://insightops.example.com`。浏览器应显示有效证书，并从 HTTP 自动跳到 HTTPS。

## 7. 首次登录后的收尾

1. 使用 `.env.prod` 中的 Owner 账号登录。
2. 在账户设置修改临时密码。
3. 把 `.env.prod` 中 `AUTH_BOOTSTRAP_ENABLED` 改为 `false`，清空 `AUTH_BOOTSTRAP_PASSWORD`。
4. 执行 `bash scripts/deploy-prod.sh latest` 让配置生效。
5. 通过“用户管理”邀请 Alpha 用户，不开放公共注册。

## 8. 配置 GitHub 手动部署

在 GitHub 仓库进入 `Settings -> Environments`，创建 `production`，建议启用审批。再在 `Settings -> Secrets and variables -> Actions` 增加：

- `DEPLOY_HOST`：服务器公网 IP 或域名。
- `DEPLOY_USER`：例如 `ubuntu`。
- `DEPLOY_PATH`：`/opt/insightops-agent`。
- `DEPLOY_SSH_KEY`：只用于部署的 SSH 私钥全文。
- `DEPLOY_KNOWN_HOSTS`：在可信终端运行 `ssh-keyscan -H 服务器域名` 得到的整行内容；首次连接前应通过云控制台核对主机指纹。

CI 完成后，三个镜像标签使用完整 Git 提交 SHA。进入 `Actions -> Deploy production -> Run workflow`，填写该 SHA。部署脚本会先备份数据库，升级后检查健康状态，失败则恢复上一镜像标签。

如果 GHCR 包尚未公开，把三个 package 的可见性设置为 Public；否则需在服务器配置仅有 `read:packages` 权限的 GitHub Token 并执行 `docker login ghcr.io`。

## 9. 访问监控

Grafana 没有暴露公网。先在本机建立 SSH 隧道：

```bash
ssh -L 3000:127.0.0.1:3000 ubuntu@服务器公网IP
```

再访问 `http://127.0.0.1:3000`，使用 `.env.prod` 的 Grafana 账号密码登录。

## 10. 上线前最终检查

- HTTPS 有效，HTTP 自动跳转，浏览器没有混合内容警告。
- 只有 22、80、443 对公网开放。
- Owner 临时密码已修改，Bootstrap 已关闭。
- DeepSeek Key 未出现在 Git、日志和截图中。
- 登录连续失败会返回 429，正常登录不受影响。
- 聊天、RAG、执行记录、用户隔离均通过。
- 手工执行一次备份，并把副本同步到另一台机器或私有对象存储。
- 做一次恢复演练后再邀请真实 Alpha 用户。

## 官方参考

- Docker Engine on Ubuntu: <https://docs.docker.com/engine/install/ubuntu/>
- Docker Linux post-install: <https://docs.docker.com/engine/install/linux-postinstall/>
- Caddy Automatic HTTPS: <https://caddyserver.com/docs/automatic-https>
- GitHub Actions encrypted secrets: <https://docs.github.com/actions/security-guides/using-secrets-in-github-actions>
- GitHub Container Registry: <https://docs.github.com/packages/working-with-a-github-packages-registry/working-with-the-container-registry>
