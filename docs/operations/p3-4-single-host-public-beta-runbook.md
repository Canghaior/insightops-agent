# P3.4 单机公开 Beta 运行手册

## 运行边界

InsightOps 当前是个人运营的免费公开 Beta，部署在中国大陆单台服务器，使用同机 PostgreSQL 18 和本地上传卷。它不宣称多可用区、高可用数据库或商业 SLA。首批硬上限为 100 个已占用名额，每个公开 Beta Workspace 同时最多创建 1 个 Agent Run；注册与公开 Run 可分别紧急停止。

## 内部目标（不是对外 SLA）

| 指标 | 内部目标 | 处置 |
|---|---:|---|
| 月度可用性 | 99.0% best effort | 低于目标时先停新注册，再限制公开 Run |
| HTTP 5xx 比例 | 10 分钟 < 5% | Alertmanager 告警并检查 Server/PG/模型依赖 |
| 公共状态接口 p95 | < 1.5 秒 | 运行容量脚本并检查 CPU、内存、连接池 |
| RPO | <= 24 小时 | 每日生成数据库和上传文件的 AES-256 异地包 |
| RTO | <= 4 小时人工恢复目标 | 从 30 天 Artifact 回取，在隔离 PG18 验证后恢复 |

已完成的生产证据是：10 项目 72 小时稳定性门禁、18 条 Prometheus 规则、私密 ntfy 到达、AES-256 GitHub Artifact 30 天和独立 PostgreSQL 18 隔离恢复。公开 Beta 不把这些内部目标表述为商业保证。

## 发布前门禁

1. 三个腾讯云邮件模板必须为“审核通过”，CAM 子用户只能拥有 `ses:SendEmail` 与 `ses:GetSendEmailStatus`。
2. Turnstile 必须限定 `insightops.canghaior.com`，服务端校验 hostname 与 action=`register`。
3. 配置运营者显示名称和公开联系邮箱；注册页面状态必须先显示 READY。
4. 先保持数据库 `registration_enabled=false`，部署和 Smoke 通过后再由 `/admin/public-beta` 开启。
5. 运行后端真实 PG18、前端测试/构建、安全工作流和 20 VU 容量基线。

## 故障操作顺序

1. AI 或队列异常：关闭 `runs_enabled`，不影响登录、导出和账号删除。
2. 邮件、Turnstile、注册滥用或容量接近 100：关闭 `registration_enabled`，已有用户继续使用。
3. 数据库或文件系统风险：关闭两个开关，保存现场，执行加密备份；不得直接覆盖生产卷。
4. 发布回归：使用上一个已验证镜像 SHA 回滚，再执行 health、Flyway 版本和登录 Smoke。
5. 恢复：先运行 `scripts/restore-offsite-drill.sh` 在隔离 PG18 验证，确认摘要后才进入生产恢复流程。

## 容量测试

```bash
BASE_URL=https://insightops.canghaior.com VUS=20 HOLD=3m bash scripts/p3-4-capacity-test.sh
```

默认只压公开只读状态接口，避免产生用户、邮件或模型费用。正式开放前逐级运行 20、50、100 VU；任何一级超过 1% 错误或 p95 1.5 秒即停止扩容测试并保持注册关闭。

## 隐私操作

- 导出快照排除密码、会话 Token 和密钥；文件以现有身份加密密钥进行 AES 加密，令牌一次性使用，默认 24 小时过期。
- 公开个人 Workspace 删除在宽限期后清理用户会话、Run、聊天、记忆、上传记录和物理文件，账号保留为匿名占位；共享 Workspace 继续要求转移所有权。
- 异地加密备份中的旧副本最长 30 天自然过期；必要的去标识化统计和安全审计按政策保留。
