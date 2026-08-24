# 公开 SaaS Go/No-Go 决策（2026-08-24）

## 1. 决策

| 决策对象 | 结论 |
|---|---|
| 继续研发公开 SaaS 所需基础能力 | **CONDITIONAL GO** |
| 继续运行当前封闭 Alpha / 管理员邀请制受控 Beta | **GO** |
| 现在开放陌生用户自主注册 | **NO-GO** |
| 现在开放付费 SaaS | **NO-GO** |

产品的 Agent、RAG、工作流、成本技术治理、生产告警和灾难恢复已经证明核心价值与可运维性，值得进入 SaaS 基础能力建设；但当前只能保持封闭邀请制。不得把公网登录页、单一 Alpha Workspace 或内部成本台账解释为公开 SaaS 已完成。

本决策不启用注册、不接入支付、不创建外部客户数据，也不改变现有生产认证边界。

## 2. 硬门禁核对

| 门禁 | 当前证据 | 结论 |
|---|---|---|
| 账号全生命周期 | 只有管理员创建账号、登录、本人改密和管理员重置；没有注册、邮箱验证、用户自助找回、MFA、账户注销 | **NO-GO** |
| 多租户 Workspace | 数据表具备 Workspace 隔离键，但登录固定选择首个成员关系；用户不能创建/切换 Workspace，没有邀请链接、所有权移交、成员退出或项目级权限 | **NO-GO** |
| 商业套餐与计费 | 已有 Workspace 日/月 Token、成本、并发预占和硬拒绝；没有套餐权益、订阅、订单、支付、支付 Webhook、退款、发票或财务对账 | **NO-GO** |
| 合规与数据权利 | 仓库没有用户协议、隐私政策、数据保留/导出/删除流程和面向用户的账户删除闭环 | **NO-GO** |
| 安全与滥用防护 | 已有 HTTPS、Secure/HttpOnly Cookie、登录限流、CSP、安全响应头、非 root 容器和依赖更新；仍缺 MFA、持久/分布式限流、验证码/机器人防护、SAST、镜像与 Secret 扫描、WAF/CDN/DDoS、安全事件流程；生产响应缺少 HSTS | **NO-GO** |
| 可靠性与恢复 | 10 项目 72 小时稳定性、18 条告警、外部 Canary、AES-256 异地 Artifact 和 PostgreSQL 18 隔离恢复已通过；但生产仍是单主机、单 PostgreSQL、单 Server/Worker，没有公开 SaaS 可用性目标和事故响应承诺 | **CONDITIONAL** |

公开 SaaS 完成标准共 6 组，目前只有“异地备份、恢复演练和外部告警”完整通过。任一账号、合规或安全硬门禁未关闭时，公开发布保持 NO-GO。

## 3. 代码与生产证据

- `docs/architecture/p1-account-administration.md` 明确产品是封闭邀请制且不存在匿名注册接口。
- `AuthController` 仅提供 login、me、logout 和本人改密；`AccountAdminController` 只允许管理员创建/启停/重置用户。
- 前端路由只有 `/login`；登录页明确显示“当前为封闭邀请制，不开放自主注册”。
- `AccountWorkspaceStore.AccountRecord` 只携带一个 Workspace；`JdbcAccountWorkspaceStore` 对成员关系排序后 `limit 1`，没有 Workspace 选择上下文。
- V29 是技术成本治理台账，不是商业订单或支付账本。
- CI 运行 Maven、前端测试/构建和镜像构建，Dependabot 覆盖 Maven/npm/Actions；没有 CodeQL、SAST、容器漏洞、Secret 泄漏、SBOM 或签名门禁。
- 生产 `https://insightops.canghaior.com/` 返回 HTTP 200，匿名 `/api/v1/auth/me` 返回 HTTP 401。
- 生产响应已有 CSP、`nosniff`、`DENY`、Referrer Policy 和 Permissions Policy；`Strict-Transport-Security` 当前缺失。
- Stage 3 生产可靠性证据见 `docs/testing/results/stage3-production-reliability-2026-08-24.md`。

## 4. 允许继续的运营边界

在下一次 Go/No-Go 前，只允许：

- 当前管理员邀请制 Alpha；
- 少量、身份已核验且明确知情的受控 Beta 用户；
- 管理员手工创建和停用账号；
- 现有 Workspace 技术配额、审计、告警、备份与恢复机制。

暂不允许：

- 在页面或宣传中提供“立即注册”；
- 接收在线付款或承诺发票、退款、服务等级；
- 自动创建陌生用户 Workspace；
- 把当前 ntfy 主题作为面向客户的支持或安全事件渠道；
- 在没有数据删除与合规文本的情况下扩大收集个人数据。

## 5. 重新评审的最短闭环

### P3.1 身份与团队

1. 邮箱身份、验证、找回密码、MFA、全会话撤销、账户注销。
2. Workspace 创建、邀请接受、切换、角色、所有权移交、成员退出和删除。
3. 注册/验证/找回/邀请接口分别实施持久限流、一次性令牌、过期、重放拒绝和审计。

### P3.2 安全与合规

1. 用户协议、隐私政策、数据保留、导出和级联删除流程，经适用辖区的专业审阅后发布。
2. CodeQL/SAST、依赖审查、容器与 Secret 扫描、SBOM、镜像签名和发布门禁。
3. CDN/WAF/DDoS 与机器人防护、HSTS、持久/分布式限流、安全事件分级和响应演练。

### P3.3 套餐与商业闭环

1. 先把现有技术配额抽象为不可变套餐权益与版本化订阅状态。
2. 选择支付与开票方案后，再实现订单、幂等支付 Webhook、退款、对账和审计。
3. 免费 Beta 可以暂不收费，但仍必须关闭身份、合规、安全和滥用硬门禁。

### P3.4 可用性

1. 定义公开 Beta 的 SLO、容量上限、维护窗口、状态页和事故响应责任。
2. 消除登录限流等进程内单点状态，验证多实例会话与 Worker 扩展。
3. 明确 PostgreSQL、上传对象和密钥的生产冗余/RPO/RTO，再做一次带测时的恢复演练。

## 6. 下一次判定规则

- **公开免费 Beta GO**：P3.1、P3.2 和 P3.4 的所有硬门禁通过生产验收；合规文本已获适用辖区专业审阅；容量与滥用上限明确。
- **公开付费 SaaS GO**：在免费 Beta GO 基础上，P3.3 的订单、支付、退款、对账和客户支持闭环通过生产验收。
- 未满足上述条件时保持 **NO-GO**，不以“功能大多可用”或单次 Smoke 替代门禁。

## 7. 阶段结论

第 4 步已经完成决策：InsightOps Agent 应继续向 SaaS 基础能力发展，但当前不得公开注册或收费。项目保持封闭 Alpha/受控 Beta，下一实施阶段应从 P3.1 身份与团队开始，而不是直接接支付或扩大公网流量。
