# P2.4-C 工作流产品化验收记录（2026-08-24）

## 1. 实现范围

- 管理端可拖拽 DAG 画布、SVG 依赖边和可视依赖增删。
- 节点 `position` 元数据进入不可变模板版本图，但不参与执行语义。
- 用户级、活动版本绑定的运行参数预设；保存前执行输入类型和必填校验。
- `schemaVersion = 1` 的模板 JSON 导出与重新校验导入。
- 1～90 天、可撤销、可审计的模板分享；数据库只保存 SHA-256 令牌哈希。
- 分享令牌使用浏览器 fragment 和 POST body，不进入页面查询串或 API 路径。
- 基于真实 Run、节点、回答反馈、引用反馈、Token、费用和耗时的模板质量趋势。
- 多成员反馈先按 Run 聚合，避免重复计算 Run、Token、费用和节点。

## 2. 自动化结果

| 门禁 | 结果 |
|---|---|
| 后端全仓真实数据库 `mvn verify` | PASS：280/280，Failures 0，Errors 0，Skipped 0 |
| PostgreSQL / Flyway | PASS：PostgreSQL 18.4，Flyway 37/37 |
| P2.4-C 数据库门禁 | PASS：预设 upsert/delete、分享创建/导入计数/撤销、真实 Run 质量聚合、多用户反馈去重 |
| P2.4-C 服务与权限测试 | PASS：活动版本绑定、输入校验、令牌只存哈希、导入复用合同门禁、Owner/Member 权限 |
| DAG 布局回归 | PASS：`position` 经规范化预检后保留；旧图使用确定性默认布局 |
| 前端 Vitest | PASS：23 文件，63/63 |
| 前端静态与生产构建 | PASS：ESLint、`vue-tsc`、Vite build |
| 生产验收脚本 | PASS：`bash -n`；临时 Cookie 与请求文件使用 077 权限并在退出时清理 |

## 3. 提交、CI 与部署

- P2.4-C 生产代码提交：`9563234ec10b3a3efee78f6f6c5f05c3367b6151`。
- CI Run `32716387888`：backend、frontend、server image、worker image、web image 五个 Job 全部成功。
- Deploy Run `32716895893`：成功，精确部署上述完整 SHA。
- 验收语义修复提交：`f64e083c531dee4ad76afec6392e2f735f78bbbd`。
- 修复提交 CI Run `32717652958`：五个 Job 全部成功；该提交只修改验收脚本与架构文档，生产应用镜像仍为 `9563234...`。

## 4. 生产公开 Smoke

- `https://insightops.canghaior.com/admin/agent-workflows`：HTTP 200。
- `/api/v1/system/status`：`service=insightops-server`、`status=UP`。
- DeepSeek：`deepseek-v4-flash`、`ready=true`。
- 未登录访问 P2.4-C 管理 API：HTTP 401。

## 5. 认证态产品验收

首次 Run `32717207428` 正确完成 API 操作，但验收脚本把“模板容器 `ACTIVE`、`activeVersionId=null`、v1 `DRAFT`”误断言为模板容器 `DRAFT`，因此以失败结束。业务实现未失败；参数预设已删除、分享已撤销，导入版本保持未激活。提交 `f64e083...` 修正了验收语义。

替代 Run `32717865872` 全部通过，使用来源模板“版本对比”：

- 来源模板：`2b7bf8eb-ae07-4428-96c5-eebdb56d05cd`。
- 来源版本：`d1e8d697-c285-493c-81ca-4f2e96f46661`。
- 临时参数预设：`66cc6182-aac4-47f7-b4b8-08b86827465a`，查询验证后删除。
- 分享记录：`6159994c-dcb7-49ad-9de5-f76fbb0d6b35`，预览和导入后撤销；撤销后预览返回 HTTP 400；审计 `importCount=1`。
- 替代导入模板：`1e6a63fa-7376-4be5-a70b-9f48dfd2f06c`，模板容器可管理，`activeVersionId=null`，v1 为 `DRAFT`，不能启动 Run。
- 导出包 `schemaVersion=1`、图节点非空；质量趋势 30 天窗口和汇总合同有效。
- 分享列表不返回原始令牌；原始令牌没有写入数据库或验收日志。

首次失败 Run 和替代 Run 各留下一个带 Run 标记的未激活 `DRAFT` v1 导入模板作为生产审计证据；二者均不能驱动 Run，也未调用模型、未创建 Agent Run、未产生 Token 或费用。

## 6. 安全与恢复结论

- 分享不能提升 Workspace 或工具权限；预览和导入要求 Owner/系统管理员认证。
- 过期或撤销令牌统一失效；来源 Workspace 才能撤销自己的分享。
- 导入不保留来源数据库 ID、活动版本、Run、用户数据、审批、费用或令牌，并重新执行图和工具合同校验。
- 预设只允许所有者在当前活动版本下读取、保存和删除。
- P2.4-C 不改变 P2.4-B 的 Run 快照、租约 fencing、安全点、成功节点复用、写工具审批和成本单次结算边界。

## 7. 阶段结论

P2.4-C 的功能、真实 PostgreSQL、前端、CI、部署、公开 Smoke 和认证态生产验收均已关闭。下一阶段按既定顺序进入“10 项目长期稳定性 + Alertmanager + 异地恢复演练”。
