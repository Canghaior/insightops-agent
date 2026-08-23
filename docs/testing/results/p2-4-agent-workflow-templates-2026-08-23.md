# P2.4-A 工作流模板与预检验收记录（2026-08-23）

## 1. 实现范围

- V35 工作流模板、不可变版本、活动版本和激活审计。
- DAG、工具可用性、工具参数、并行写节点的运行前门禁。
- 四类内置研究模板。
- Owner/系统管理员管理 API。
- 模板库、节点编辑、条件依赖和分层 DAG 预览页面。
- 入口研究问题带入现有研究问答。

## 2. 自动化结果

| 门禁 | 结果 |
|---|---|
| 后端编译与现有单元测试 | PASS：263 tests，Failures 0，Errors 0；其中 21 个真实数据库门禁在普通测试命令中按设计跳过 |
| P2.4-A 图校验单元测试 | PASS：分层并行、循环依赖、非法参数和写工具审批警告 |
| P2.4-A API 权限测试 | PASS：Owner 可管理，普通 Member 返回 403 |
| PostgreSQL V35 门禁 | PASS：PostgreSQL 18.4，Flyway 35/35，版本递增、原子激活、旧版本退役、两条审计流水和跨 Workspace 隔离 |
| 全链路 `mvn verify` | PASS：263/263，Failures 0，Errors 0，Skipped 0；所有真实 PostgreSQL 门禁已执行 |
| 前端 lint | PASS |
| 前端 Vitest | PASS：21 文件，53/53 |
| 前端类型与生产构建 | PASS：`vue-tsc` 与 Vite build |

## 3. 安全结论

预检不会创建 Run 或调用工具。模板激活不提升工具权限；写工具仍由 P2.0-D 审批、幂等和补偿边界控制。模板版本不就地修改，激活操作有审计记录。

## 4. 阶段边界

P2.4-A 关闭“模板持久化 + 可视化预检”闭环。活动模板直接驱动真实 Run、依赖结果表达式和失败节点重跑明确留给 P2.4-B。

## 5. 生产发布结果

- 生产提交：`ca9c2fcfd8525c5c328907c4ca21f6a392706fee`。
- GitHub CI Run `32621114400`：PASS，后端、前端与 Server/Worker/Web 镜像构建全绿。
- GitHub Deploy Run `32621259016`：PASS，生产迁移、镜像更新与健康检查成功。
- 公网 `/api/v1/system/status`：`UP`，DeepSeek `deepseek-v4-flash` `ready=true`。
- 公网 `/admin/agent-workflows`：HTTP 200；未登录访问管理 API 返回 HTTP 401，权限边界生效。
