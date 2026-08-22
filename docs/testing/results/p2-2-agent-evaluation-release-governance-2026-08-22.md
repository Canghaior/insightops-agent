# P2.2-A/B/C Agent 评测与发布治理验收

日期：2026-08-22

状态：P2.2-A/B/C 已完成全量门禁、提交、CI、生产部署、Owner 登录态评测、候选激活和激活后新 Run 验收，生产闭环已关闭。

## 已验收范围

- 不可变版本化评测集、必过案例与七类质量/性能/成本门槛。
- 真实 AgentLoop 离线评测，评测模式只暴露只读工具并拒绝写节点。
- Prompt、模型、temperature、最大输出 Token 与工具合同 SHA-256 候选版本。
- 未通过评测禁止激活，通过后 Workspace 级事务锁与原子版本切换。
- 评测 Run 与普通聊天 Run 隔离，逐案例关联完整 Agent Run Trace。
- Owner/系统管理员页面、异步轮询、趋势指标和失败 Run 反馈沉淀。
- V30/V31/V32 在 PostgreSQL 18.4 空 Schema 上完成全量迁移。

## 自动化结果

| 门禁 | 结果 |
|---|---|
| P2.2 服务层定向测试 | PASS：候选工具合同指纹、未知工具拒绝、真实 Loop 评测与聚合持久化 |
| P2.2 管理 API 权限测试 | PASS：Owner、系统管理员可访问，普通成员返回 403 |
| P2.2 PostgreSQL 门禁 | PASS：Flyway 32/32；未通过禁止激活、结果持久化、通过后激活、Run 类型隔离 |
| Maven 全仓 `test` | PASS：231 个测试槽位；213 通过，18 条数据库门禁按默认环境跳过 |
| PostgreSQL 全仓 `verify` | PASS：231/231，0 失败、0 跳过 |
| 前端 ESLint | PASS |
| 前端 Vitest | PASS：20 个测试文件，46/46 |
| 前端生产构建 | PASS：Vue TypeScript 检查和 Vite build |
| GitHub CI | PASS：Run `32552306868`，提交 `0b0d2d9e123d2dc3e6bcba4a6daa5fd2a7c6a904` |
| GitHub 生产部署 | PASS：Run `32552470905`；备份、部署、健康检查与回滚保护步骤全部成功 |
| 生产缺陷修复门禁 | PASS：统一以任务图规范终态 `COMPLETED` 判定成功；提交 `ac839cb06aeea1723c388339274372ea406def3d`；真实 PostgreSQL `verify` 231/231，Flyway 32/32 |
| 修复 CI 与生产部署 | PASS：CI Run `32556609323`、生产部署 Run `32556829102` 均成功 |
| 公网只读验收 | PASS：首页与 `/admin/agent-evaluations` 返回 200；系统状态为 `UP`、模型就绪；未认证管理 API 返回 401；管理页 JS/CSS Chunk 可访问 |

## 生产登录态验收

- Owner 在 `/admin/agent-evaluations` 创建了 1 题不可变数据集 `p2-2-production-smoke-20260822` v1 和候选版本 `production-default-smoke-20260822` v1。
- 首次评测 Run `a2186aa0` 保留为失败审计记录：工具准确率 100%，但旧评测器未识别任务图规范终态 `COMPLETED`；没有改写历史结果。
- 修复部署后复测 Run `4aa02f4a` 通过：成功率 100%、工具准确率 100%、平均耗时 12.28 秒、平均费用 ¥0.003813。
- 候选版本 v1 已激活为当前生产配置，模型为 `deepseek-v4-flash`；后续新 Run 使用该配置。
- 激活后聊天 Run `d2e611a5` 生成完成：两次 `knowledge_hybrid_search` 均成功，答案包含可核验的 `docs.spring.io` 官方来源；首 Token 758 ms、总耗时 3444 ms。
- 管理 API 自动化权限测试已验证 Owner/系统管理员可访问、普通成员返回 403；生产页面与 API 共同覆盖角色边界。

## 当前边界

- 评测执行器当前为单实例进程内异步队列，尚未实现服务重启后的自动接管。
- 首次失败 Run 作为不可变审计证据保留；状态判定修复没有回写历史评测结果。
- P2.2 生产闭环已关闭；下一阶段应建设可恢复的持久化评测队列、灰度发布/自动回滚和更大规模回归集。
