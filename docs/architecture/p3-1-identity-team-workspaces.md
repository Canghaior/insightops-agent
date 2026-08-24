# P3.1 身份生命周期与团队 Workspace

## 1. 目标与边界

P3.1 将 InsightOps 从固定 Alpha Workspace 提升为可受控邀请的多 Workspace 产品，关闭邮箱身份、密码找回、MFA、会话管理、账户注销申请，以及 Workspace 创建、切换、邀请、角色、所有权和退出闭环。

本阶段仍不开放陌生用户自主注册，也不接入套餐、支付、发票或法律级数据删除。生产运营边界保持“管理员邀请制封闭 Alpha / 少量受控 Beta”；公开免费 Beta 和付费 SaaS 仍为 `NO-GO`。

## 2. 数据模型

Flyway V38 在现有 `app_user`、`workspace`、`workspace_member` 和 `auth_session` 上扩展：

- 用户邮箱、标准化邮箱、验证时间、MFA 状态、删除计划和匿名化时间；
- Workspace slug、描述、状态、创建者、更新时间和归档时间；
- Session 的活动 Workspace、User-Agent、IP 单向指纹与最后活跃时间；
- 只保存 SHA-256 哈希的一次性身份令牌和 Workspace 邀请；
- AES-256-GCM 加密的 TOTP Secret 与邮件正文；
- 单次使用的恢复码哈希；
- 可租约领取、可重试的持久邮件 Outbox；
- PostgreSQL 持久身份限流状态和账户删除申请。

所有新表都以 Workspace 或 User 为明确归属，不把原始邀请、验证、重置令牌、TOTP Secret、恢复码或邮件正文以明文持久化。

## 3. 身份生命周期

### 3.1 邮箱和密码

- 已登录用户用当前密码请求设置或变更邮箱。
- 验证、重置和邀请令牌均使用 256 bit 随机值；数据库只保存哈希、用途、到期时间、消费时间和吊销时间。
- 邮箱验证和密码重置均为一次性消费，重放和过期请求拒绝。
- 忘记密码始终返回统一 `202`，不泄露邮箱是否存在。
- 密码变更、重置、MFA 禁用和账户删除会按边界撤销会话。

### 3.2 MFA 和恢复码

- TOTP 使用标准 30 秒窗口及受限时钟偏移；数据库以单调时间步原子消费，跨实例不能重复使用同一验证码。
- Setup 阶段仅保存加密的待确认 Secret；正确 TOTP 后才启用 MFA。
- 恢复码只显示一次且数据库只保存哈希，每个码只能消费一次。
- 启用 MFA 后登录先校验密码，再要求 TOTP 或恢复码；错误响应不泄露更多账号状态。

### 3.3 Session 与注销

- 用户可查看设备、创建/活跃/过期时间、地址指纹和活动 Workspace，并撤销单个或其他全部 Session。
- 删除账户先进入宽限期并立即撤销会话；宽限期内可重新认证取消。
- 到期调度器将账号停用并匿名化身份字段、凭据和 MFA 材料。
- P3.1 的“删除”是身份匿名化边界，不声称完成项目内容、审计、法定留存和备份的完整级联擦除；这些属于 P3.2。

## 4. 团队 Workspace

- 当前用户可以创建有唯一 slug 的 Workspace，并成为 Owner。
- Session 保存明确的 `active_workspace_id`；切换只允许当前用户的活动成员关系。
- Owner 可以维护 Workspace 资料、邀请 Member/Owner、撤销邀请、调整角色、移除成员、移交所有权和归档 Workspace。
- 成员可退出，但系统禁止产生无 Owner Workspace；归档当前 Workspace 前必须保留另一个活动 Workspace。
- 成员移除、退出或 Workspace 归档时，相关 Session 自动迁移到该用户另一个活动 Workspace；没有替代 Workspace 才撤销，避免无效租户上下文长期挂起。
- 所有管理变更与现有账号审计写入同一事务；审计失败时业务变更回滚。

当前角色继续使用现有 `OWNER` / `MEMBER`，系统管理员保留跨 Workspace 管理能力。Viewer/Admin 的更细权限和项目级 ACL 不在 P3.1 范围。

## 5. 邮件交付

邮件实现采用提供商中立 SMTP：

- 业务事务只写入加密 Outbox，不阻塞用户请求等待 SMTP；
- 多实例通过持久租约领取，成功、重试和永久失败都有状态；
- 日志不记录收件人、正文和原始令牌；
- SMTP 默认关闭。关闭时不会伪造“已发送”，仅向已认证 Owner 返回一次性手工验证/邀请链接；匿名密码找回从不返回链接；
- 生产可通过环境变量启用任意兼容 SMTP 服务，不绑定单一厂商。

## 6. API 与前端

| 能力 | API | 页面 |
|---|---|---|
| 邮箱、MFA、Session、注销 | `/api/v1/identity/**` | 账号设置 |
| 邮箱验证、找回与重置 | `/api/v1/public/identity/**` | `/verify-email`、`/forgot-password`、`/reset-password` |
| Workspace 管理 | `/api/v1/workspaces/**` | `/workspace` 与全局切换器 |
| 邀请预览/新用户接受 | `/api/v1/public/invitations/**` | `/invitation` |

前端所有非安全方法自动发送 `X-InsightOps-CSRF: 1`。服务端对全部 `/api/**` 写请求强制该标记，并在存在 `Origin` 时要求与配置的公网 Origin 完全一致。Caddy 统一发送 HSTS。

## 7. 安全与并发

- 登录、找回、验证、重置和邀请入口使用 PostgreSQL 持久限流；事务级 advisory lock 使多实例并发计数原子化。
- 登录 Session 继续使用 HttpOnly、Secure、SameSite Cookie，数据库只保存 token 哈希。
- 邀请和身份令牌只允许进入 URL fragment 或 POST body，不进入查询日志和服务日志。
- Workspace 写操作以数据库约束、事务和服务端授权为最终边界，前端隐藏按钮不是权限控制。
- V38 保留既有 Alpha Owner 和 Alpha Workspace，并为旧 Session 回填活动 Workspace，升级不要求重建账号。

## 8. 运维与验收

- 生产新增 SMTP、身份加密密钥、令牌有效期、删除宽限期和限流配置；邮件关闭时系统仍保持正确的未投递状态。
- `scripts/p3-1-production-acceptance.sh` 只创建一个唯一临时 Workspace 和一个 `.invalid` 邀请，随后撤销邀请并归档 Workspace，不创建真实外部联系人。
- 验收覆盖 HSTS、匿名 401、无 CSRF 403、身份摘要、Session、创建/切换 Workspace、Owner 成员关系、邀请无令牌泄漏、撤销、枚举安全的找回响应和清理。
