# P3.1 身份与团队 Workspace 测试结果（2026-08-25）

## 1. 范围

本轮覆盖 V38 身份/团队数据模型、邮箱验证与找回、TOTP/恢复码、设备 Session、账户删除宽限期、持久限流、加密邮件 Outbox、Workspace 创建/切换/邀请/角色/所有权/退出/归档、前端闭环、CSRF 和 HSTS。

`OrcaTerm/` 是用户文件，本轮未读取、未删除、未加入提交。

## 2. 自动化结果

| 门禁 | 结果 |
|---|---|
| 后端完整 Maven verify | PostgreSQL 18.4，Flyway 38/38，294/294，Failures 0、Errors 0、Skipped 0 |
| P3.1 数据库专项 | 一次性令牌、活动 Workspace、退出回落、删除所有权复核、持久限流、TOTP 防重放、恢复码和邮件租约通过 |
| 前端 Vitest | 68/68 通过 |
| ESLint | 通过 |
| Vue TypeScript + Vite 生产构建 | 通过 |

聊天 CSRF 回归修复后的最终冻结代码由 CI Run `32843561951` 完成全量验证：Core 20/20、Infrastructure 39/39、Server 219/219、Worker 16/16，共 294/294；前端 68/68、ESLint、Vue TypeScript、Vite 生产构建和三个生产镜像任务全部成功。

## 3. 关键安全断言

- 原始身份/邀请令牌不落库，消费后不能重放。
- AES-256-GCM 密文被篡改后拒绝解密。
- TOTP 时间步和 MFA 恢复码都只能消费一次，跨实例重放拒绝。
- 登录与身份入口限流跨实例持久且并发更新原子化。
- 写 API 缺少 CSRF 标记返回 403，跨 Origin 请求拒绝。
- 忘记密码对存在和不存在邮箱保持统一响应。
- 成员退出或 Workspace 归档后 Session 回落到另一个活动 Workspace；没有替代项才撤销。
- Workspace 管理变更和审计保持事务一致。

## 4. 生产验收

受保护工作流 Run `32826199893` 与修复后复验 Run `32844206799` 均已通过，覆盖：

- HSTS 和服务健康；
- 匿名 Workspace API 401；
- 认证写请求缺 CSRF 403；
- 身份摘要和当前设备 Session；
- 临时 Workspace 创建、切换、Owner 成员关系；
- `.invalid` 邀请创建、列表不泄漏令牌、撤销；
- 未知邮箱找回统一 202；
- 切回 Alpha Workspace 并归档临时 Workspace。

发布追踪：

- P3.1 主提交 `240ca3fb95c8acff7b774f3f81b19ab650434056`，CI Run `32809649617` 成功。
- 首次部署 Run `32810068218` 暴露 Spring 构造注入问题并自动回滚；修复提交 `2139a018733b2c8b5f93d171e437b694cc6f328f`，CI Run `32811220764`、部署 Run `32811497830` 成功。
- Caddy 配置激活修复提交 `209d88d839d6c38e92eb3c6196bfa27b13bec23b`，CI Run `32824432131`、部署 Run `32824775308` 成功，生产 HSTS 生效。
- 验收脚本 Session 断言修复提交 `f1eb515c9d88dc5b9de861f715bfccb5f2e336a8`，最终 CI Run `32825930469` 成功。
- 最终生产验收 Run `32826199893` 成功，临时 Workspace 已切回并归档，没有遗留活动测试 Workspace。
- 聊天原生 `fetch` CSRF 修复提交 `15dc3a0c5098421c89faa54148e2c94e56ed3f5a`，CI Run `32843561951`、部署 Run `32843905374` 和认证聊天复验 Run `32844206799` 成功；生产哈希 ChatView 资产确认以共享常量发送 `X-InsightOps-CSRF: 1`。

## 5. 结论

P3.1 的代码、全量自动化、生产部署和受保护生产验收均已完成。公开注册、商业计费、法律级数据导出/级联删除、安全供应链和公开 Beta 可用性仍不在本阶段完成范围，公开 SaaS 继续保持 `NO-GO`，后续进入 P3.2/P3.3/P3.4。
