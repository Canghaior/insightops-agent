# P3.4 单机免费公开 Beta 可用性边界

## 1. 运行模型

P3.4 面向首批最多 100 人的个人免费 Beta，继续使用一台中国大陆服务器、同机 PostgreSQL 18、本地上传卷和现有 Docker Compose。它不包装成多可用区、高可用数据库或商业 SLA；目标是在不增加第二台服务器、云数据库或 COS 的前提下，把单机可观测性、故障停止、备份恢复和发布供应链做到可运营。

内部目标为月度 99.0% best effort、公开状态接口 p95 小于 1.5 秒、RPO 不超过 24 小时、人工 RTO 不超过 4 小时。这些是运营目标，不是对用户的赔偿承诺。

## 2. 可观测性与告警

现有 Server、Worker、PostgreSQL、宿主机、模型、队列、磁盘、备份和接管告警继续保留；P3.4 新增：

- 注册名额达到 90% 和满额；
- 身份邮件永久失败和最老待发邮件超过 10 分钟；
- 腾讯云 SES 配置未就绪；
- 10 分钟内 Turnstile 非接受结果超过 20 次。

规则由独立文件加载进 Prometheus，并继续通过现有 Alertmanager 私密 ntfy 通道外发。Grafana/Prometheus/Alertmanager 只绑定服务器回环地址，不直接暴露公网管理端口。

## 3. 两个紧急停止开关

- `registration_enabled=false`：停止新注册，不影响已验证用户登录、导出和删除申请；
- `runs_enabled=false`：禁止公开 Beta Workspace 创建新 Run，不影响身份与隐私操作。

开关持久化在 PostgreSQL，写入审计；公开 Run 还由 V40 数据库触发器兜底。处置顺序通常是先停新注册，再停公开 Run，最后才进入全站维护或恢复流程。

## 4. 数据保护与恢复

生产继续每日联合备份 PostgreSQL 和上传文件，生成校验清单，以 AES-256 加密后保存到 GitHub Actions Artifact 30 天。恢复必须先在隔离 PostgreSQL 18 中验证 Flyway、业务计数和文件摘要，不能直接将未验证的备份覆盖生产卷。

个人导出使用独立持久卷。卷初始化容器只负责建立目录并将所有权交给非 root Server UID 10001；Server 继续以非 root 身份运行。过期调度器删除到期或已消费导出文件。

## 5. 发布供应链

- CodeQL 对 Java 与 JavaScript/TypeScript 执行 SAST；
- Dependency Review 检查新增依赖；
- Gitleaks 扫描 Git 历史和当前提交；
- Trivy 扫描源码文件系统与三个生产镜像；
- CycloneDX 生成 Maven 和 npm SBOM；
- CI 成功后以 GitHub OIDC 对 GHCR 镜像 digest 执行 keyless cosign 签名。

这些门禁不替代 Secret 定期轮换、生产 WAF/CDN/DDoS 增强和安全事件响应演练；后三项仍是后续公开规模扩大前的增强项。

## 6. 容量基线

`scripts/p3-4-capacity-test.sh` 只压测匿名只读状态接口，不创建账号、不发邮件、不调用模型。默认 20 VU、保持 3 分钟，门槛为错误率低于 1%、p95 小于 1.5 秒、p99 小于 3 秒；脚本只允许生产域名或明确的本机地址，避免误压第三方目标。

公开前先跑 20 VU；需要扩大时再逐级跑 50 和 100 VU。任何一级越过门槛都保持注册关闭，先排查 CPU、内存、Hikari 连接池、反向代理和外部依赖。

## 7. 部署与回滚

生产部署固定组合基础 Compose 与 public-beta、Tencent SES、personal-export、public-beta-monitoring 四个 overlay。预检永远先做合并配置校验；只有总开关为 true 时才强制检查全部外部参数。

发布后检查服务健康、Flyway 40、公开状态不泄密、匿名管理 API 401、注册关闭状态、Prometheus rules 和 Target。回归时使用上一个已验证镜像 SHA 回滚；涉及数据风险时先关闭注册和公开 Run、保存现场并生成加密备份，再按运行手册恢复。
