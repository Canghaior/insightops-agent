# P1 账号、角色与管理边界

## 产品决策

InsightOps Alpha 使用封闭邀请制，不提供公开注册。首个系统管理员由未提交的 `.env` 配置，之后的账号由有权限的管理员在产品内创建。普通用户和管理员共用一个工作台，管理页面按权限显示。

## 两层角色

| 角色 | 范围 | 能力 |
|---|---|---|
| `SYSTEM_ADMIN` | 整个系统 | 创建系统管理员、Owner、Member；启停账号；重置密码；调整工作区角色；查看账号审计 |
| `OWNER` | 当前工作区 | 创建、启停、重置普通 Member；查看当前工作区账号审计 |
| `MEMBER` | 当前工作区 | 使用研究问答、会话、记忆、项目关注和执行记录，无账号管理权限 |

`SYSTEM_ADMIN` 存储在 `app_user.system_role`；`OWNER/MEMBER` 存储在 `workspace_member.role`。服务端始终执行权限判断，前端隐藏菜单只用于交互体验，不作为安全边界。

## 首个管理员

启动时 `AuthService` 读取：

```properties
AUTH_BOOTSTRAP_ENABLED=true
AUTH_BOOTSTRAP_USERNAME=alpha-owner
AUTH_BOOTSTRAP_DISPLAY_NAME=Alpha Owner
AUTH_BOOTSTRAP_PASSWORD=本地安全密码
```

若用户名不存在，系统在 Alpha 工作区创建 `SYSTEM_ADMIN + OWNER`；若已存在，则提升并启用该账号。密码只在凭据不存在时写入，应用重启不会覆盖用户后来修改的密码。

## 临时密码与会话

- 管理员创建或重置账号时写入 BCrypt 哈希，不保存明文。
- 新账号和被重置账号设置 `must_change_password=true`。
- 该账号在改密前仅能调用 `/auth/me`、`/auth/password`、`/auth/logout`，其他后端接口返回 HTTP 428。
- 改密、停用账号和管理员重置密码都会撤销该用户的活动会话。
- 不存在 `/register` 之类的匿名注册接口。

## 审计

V8 添加系统角色和审计表；V9 将账号管理上线前唯一的、已有凭据的 bootstrap Owner 一次性规范为系统管理员，避免升级后错误触发“临时密码首次改密”。

`account_audit_log` 按工作区记录操作者、目标账号、动作、最小 JSON 详情和时间。当前覆盖登录成功、退出、自己改密、创建账号、启用、停用、重置密码和修改工作区角色。密码、Cookie、API Key 不进入审计详情。
