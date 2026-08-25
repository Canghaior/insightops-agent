# P3.2 免费公开 Beta 注册、安全与数据权利

## 1. 目标与非目标

P3.2 把 P3.1 的邀请制身份体系扩展为最多 100 人的免费公开 Beta，同时保持单机、个人运营和可随时停止注册的明确边界。本阶段不接入套餐、支付、退款、发票或收费条款；P3.3 商业计费被主动延期，不是免费 Beta 的发布门禁。

公开注册默认关闭。只有运营者信息、腾讯云 SES、三个审核通过的邮件模板、Cloudflare Turnstile 和生产预检全部就绪后，应用级开关才允许进入 READY；最终仍需管理员显式打开数据库注册开关。

## 2. 三层启用门禁

公开注册必须同时满足三层条件：

1. 部署配置：`PUBLIC_BETA_ENABLED=true`，运营者名称、联系邮箱、14+、100 人上限、Turnstile 和腾讯云 SES 配置完整；
2. 服务就绪：邮件 Outbox 已启用，SES 凭据与模板 ID 完整，Turnstile 服务端验证已启用且限定 hostname/action；
3. 运行开关：`public_beta_control.registration_enabled=true`。

任一条件缺失时 `/api/v1/public/identity/registration/status` 仍可匿名读取，但返回明确的关闭原因；注册写请求失败关闭，不创建用户、Workspace、邮件或名额。`scripts/preflight-public-beta.sh` 阻止“配置不完整但打开总开关”的部署。

## 3. 注册状态机与容量

- 注册入口要求用户名、显示名、可验证邮箱、强密码、Turnstile token、14+ 确认以及三个版本化协议同意。
- 服务端校验 Turnstile 的 token、hostname 和 action，不信任前端成功状态；请求地址只取可信反向代理链的最右端值并仅保存单向指纹。
- PostgreSQL 在事务内原子分配 1–100 的注册名额，防止多实例并发超售。
- 新账号先进入 `PENDING_VERIFICATION`；个人 Workspace 和成员关系在同一事务内建立但在用户激活前不可登录访问。邮箱令牌消费后，调度器在同一事务内激活用户与公开注册记录。
- 未验证申请默认 24 小时过期并释放名额；用户名或邮箱冲突、容量耗尽和验证码失败均不会产生半完成账号。
- 每个公开 Beta Workspace 继续受现有 Workspace 配额约束，并以 `hard_limit_enabled=true` 强制同时最多创建 1 个 Agent Run；Token/费用月额度在本阶段使用明确的大上限，不作为普通使用限制。

## 4. 邮件与机器人防护

邮件沿用 P3.1 的加密持久 Outbox、租约领取、重试和永久失败状态，不在注册事务内同步调用外部服务。发送适配器使用腾讯云 SES v20201002 API 的 TC3-HMAC-SHA256 签名，只从环境变量读取 CAM 子用户凭据，并按邮件用途选择验证、重置或邀请模板。

Turnstile 使用 Managed Widget。生产 Widget 仅允许 `insightops.canghaior.com`，服务端预期 action 为 `register`；超时、Cloudflare 不可用、hostname/action 不匹配和无效 token 全部失败关闭。Caddy CSP 只额外允许 `challenges.cloudflare.com` 的脚本与 Frame，其他第三方脚本仍被禁止。前端和服务端仍保留已有 CSRF/Origin 门禁与 PostgreSQL 持久限流。

## 5. 协议、隐私与同意记录

产品提供用户协议、隐私政策和可接受使用政策页面，明确免费 Beta、AI 输出限制、禁止滥用、单机 best-effort、第三方邮件/人机验证、数据类别、备份最长保留期和联系渠道。注册时四项同意分别记录文档类型、不可变版本、时间、地址指纹和 User-Agent 指纹。

这些页面根据项目真实数据流生成，不声称已获得律师或监管机构审核。公开前运营者仍须填入真实显示名称与联系邮箱，并自行核对适用法规；未来条款变更必须发布新版本，不能覆盖既有同意记录。

## 6. 数据导出与账号删除

- 已认证用户可生成 JSON 数据快照，包含账号、Workspace、会话内容、Agent Run、记忆、上传元数据和同意记录，但排除密码哈希、Session token、内部密钥和加密材料。
- 导出文件只以 AES-256-GCM 密文保存，下载 token 为 256 bit 随机值且数据库只保存哈希；默认 24 小时到期，只允许成功消费一次。
- 免费 Beta 个人 Workspace 可在密码和可选 MFA 复核后申请删除；宽限期内仍可按既有流程取消。
- 到期清理会话、聊天、Agent Run/事件/安全点、记忆、关注、上传记录和物理文件，再匿名化账号并释放公开名额；共享 Workspace 仍要求先处理所有权。
- 在线清理不伪装成瞬时抹除异地副本。AES-256 GitHub Actions Artifact 中的旧备份按最长 30 天保留期自然过期，必要的去标识化统计和安全审计按政策边界保留。

## 7. API 与页面

| 能力 | API | 页面 |
|---|---|---|
| 公开状态与注册 | `/api/v1/public/identity/registration/**` | `/register` |
| 法律文档 | 公开状态提供版本与运营者字段 | `/legal/terms`、`/legal/privacy`、`/legal/acceptable-use` |
| 数据导出 | `/api/v1/identity/exports/**` | `/privacy` |
| 公开个人账号删除 | `/api/v1/identity/public-account-deletion` | `/privacy` |
| 紧急开关 | `/api/v1/admin/public-beta` | `/admin/public-beta` |

管理员可独立关闭新注册和公开 Workspace 的新 Run。V40 在数据库插入边界再次检查 `runs_enabled`，即使某个应用实例缓存了旧状态，也不能绕过紧急停止。

## 8. 生产启用顺序

1. 等腾讯云三个模板审核通过，创建最小权限 CAM 子用户并配置真实 Secret；
2. 配置 Cloudflare Turnstile Site/Secret key、运营者名称与公开联系邮箱；
3. 保持 `PUBLIC_BETA_ENABLED=false` 部署迁移和代码，完成健康、匿名 401、关闭状态及页面 Smoke；
4. 写入完整配置后改为 `PUBLIC_BETA_ENABLED=true`，确认状态接口只报告 `REGISTRATION_SWITCH_OFF`；
5. 使用真实邮箱完成一次注册、收信、验证、登录、导出和删除申请验收；
6. 最后由管理员打开数据库注册开关，并持续观察容量、邮件、Turnstile 和错误率告警。
