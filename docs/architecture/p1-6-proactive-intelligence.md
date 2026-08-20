# P1.6 主动技术情报闭环

## 目标

P1.6 把 Release 单一更新流升级为统一 GitHub 项目事件流，并形成：

```text
GitHub Release / Issue / Pull Request / Security Advisory
  -> 增量采集与标准化
  -> source_snapshot / intelligence_event / event_evidence
  -> 用户关注规则匹配
  -> 去重站内通知与摘要候选
  -> Chat 事件证据检索
  -> 回答和引用反馈
```

## 数据模型

- `intelligence_event` 增加状态、作者、标签、风险等级与更新时间，四类来源共享同一时间线。
- `user_watch_rule` 保存用户级项目、关键词、排除词、事件类型、最低重要度和通知策略。
- `event_rule_match` 以 `(rule_id, event_id)` 保证规则命中幂等。
- `research_answer_feedback` 与 `research_citation_feedback` 关联用户、Workspace 和 Agent Run，进入管理员复核/评测候选状态。

## GitHub 采集

- Release 继续使用 Releases API。
- Issue 与 Pull Request 使用按更新时间倒序的 Issues API；带 `pull_request` 字段的记录标准化为 PR。
- Security Advisory 使用仓库安全公告 API。没有访问权限时只把该来源标记为 unavailable，不影响 Issue/PR/Release 的成功提交。
- `GITHUB_TOKEN` 为可选配置；生产推荐设置，以提高 API 额度并获得安全公告权限。
- 同一 `(project_id, source_type, external_id)` 只保留一个快照，后续采集更新快照和事件，不重复生成通知。

## 租约与进度

- 项目领取时生成 `sync_lock_token` fencing token。
- Worker 在 Release、项目事件和最终提交阶段续租并更新心跳、当前来源、发现量和新增量。
- 心跳续租要求 token 匹配、状态为 `RUNNING` 且租约未过期。
- 最终写入在事务内锁定项目行并再次验证 token；旧 Worker 不能覆盖新任务。

## 规则和通知

- 规则只允许引用当前用户已关注的项目。
- 包含关键词采用任意命中，排除关键词采用任意排除；事件类型为空表示不限类型。
- 事件重要度必须不低于规则阈值。
- 同一用户对同一事件最多生成一条 `RULE_MATCH` 通知，即使多条规则同时命中。
- 规则配置为“加入摘要”时，事件进入结构化分析队列；摘要只纳入该用户明确允许的规则命中事件。

## Chat 与安全

- 仅当问题包含 Issue、PR、安全公告、漏洞、项目情报等意图时执行 `project_intelligence_event_search`。
- 事件证据使用 `[E#]` 引用，并记录为 Agent Tool Call。
- 只允许 `https://github.com/{owner}/{repo}/issues|pull|security/advisories/...` 官方 URL 通过输出安全检查。
- GitHub 事件正文始终视为不可信外部文本，不能覆盖系统指令。

## 质量门禁

- RAG 固定评测集升级为 `p1-rag-questions-v3-50`，共50题。
- 覆盖三个项目、多语言、版本冲突、跨来源、多轮指代、提示注入、越界拒答和幻觉陷阱。
- 数据库门禁验证18个迁移以及规则、事件、通知、检索、反馈的完整闭环。
