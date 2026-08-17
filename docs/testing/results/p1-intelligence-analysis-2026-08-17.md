# P1.3 技术情报分析验收记录（2026-08-17）

## 已验收范围

- Flyway V11：分析资格、结构化分析、摘要偏好、摘要和站内通知表。
- 新 Release 自动进入分析候选；V11 前历史 Release 保持手动分析。
- 严格 JSON 解析、字段边界、官方 Release 证据校验和不可信输入隔离。
- 每工作区每日 5 次、批量 2 条、最多重试 2 次、输出上限 1600 Token。
- 情报列表/详情、更新中心情报预览、摘要、通知、摘要偏好和管理员指标页面。
- 工作区、用户关注关系和管理员权限隔离。

## 自动化结果

- `ReleaseIntelligenceAnalyzerTest`：结构化输出、不可信 Release 指令和非官方证据降级。
- `IntelligenceAnalysisRunnerTest`：成功落库调用、临时失败退避及非法输出终止。
- `IntelligenceAdminControllerTest`：`SYSTEM_ADMIN` 放行，普通用户 `403`。
- `P0ChainDatabaseGateTest`：11 个迁移、任务领取、分析完成、高风险通知、用户隔离、偏好和摘要生成。
- 前端 ESLint、Vitest（6 个测试文件、16 项）和生产构建通过。

## 本机链路结果

- Web、Server、Worker 分别在 `127.0.0.1:15173`、`127.0.0.1:18080`、`127.0.0.1:18081` 启动并健康。
- 浏览器验证情报空状态、更新中心历史分析入口、摘要设置、摘要/通知页和管理员分析指标，控制台无错误或警告。
- 数据库 Flyway 版本为 11；79 条历史 Release 中 `analysis_eligible=true` 为 0，分析记录为 0。
- 登录 API 验证情报、摘要、通知为空，摘要偏好为 `OFF`，管理员今日调用量为 0。

## 真实 DeepSeek 单条验收

经项目负责人明确确认后，对历史事件 `Spring AI v2.0.0` 执行了一次真实分析：

- 事件 ID：`553625e2-ba1d-4a18-af8b-2c80727f81c5`
- 分析 ID：`69d80ce8-dac1-4c42-ae84-698455e899f8`
- 模型：`deepseek-v4-flash`
- 状态：`SUCCEEDED`，首次尝试成功，无重试、无队列残留、无失败任务
- 结论：`MEDIUM / UPGRADE / SUFFICIENT`
- Token：输入 828，输出 306
- 估算费用：`¥0.001452`，价格快照日期 `2026-08-16`
- 官方证据：`https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0`
- 成功生成 1 条未读 `ANALYSIS_READY` 站内通知
- 浏览器验证列表、详情、中文正文、证据、Token、费用和通知均正常，控制台无错误或警告

验收结束时今日调用量为 1，数据库只有这一条情报分析；其余历史 Release 未被自动分析。
