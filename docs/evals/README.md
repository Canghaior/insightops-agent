# InsightOps Agent P0 评测集

> 文档状态：P0 基线
> 版本：v0.1
> 创建日期：2026-08-15
> 当前数据集：`p0-research-questions.jsonl`

## 1. 用途

本目录保存 InsightOps Agent 的产品问题和评测契约。问题集同时用于：

1. 约束 P0 需要实现的真实用户场景。
2. 决定工具名称、输入参数和数据字段。
3. 验证项目识别、工具选择、时间范围和来源引用。
4. 检查回答是否完整、可追溯并能正确表达不确定性。
5. 为后续模型、Prompt、工具或检索变更提供回归基线。

P0 只启用 GitHub Release 数据源，因此当前问题不得要求 Issue、Pull Request、RSS、官方文档或网页搜索才能可靠回答。

## 2. 文件格式

`p0-research-questions.jsonl` 使用 JSONL：每一行是一个独立 JSON 对象，而不是一个 JSON 数组。

要求：

- 文件使用 UTF-8。
- 每条记录只占一行。
- 行尾不添加逗号。
- `id` 全局唯一且创建后不随意修改。
- `projectIds` 必须引用 `../product/tracked-projects.yaml` 中存在的项目 ID。
- 数组即使只有一个值也保持数组格式。

## 3. 字段定义

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | 唯一问题编号 |
| `question` | string | 用户原始问题 |
| `projectIds` | string[] | 涉及的项目 ID |
| `category` | string | 问题分类 |
| `mode` | string | `live` 或 `fixture` |
| `timeWindowDays` | number/null | 最近多少天；不适用时为 `null` |
| `expectedTools` | string[] | 预期调用的工具 |
| `requiredSourceTypes` | string[] | 回答必须使用的来源类型 |
| `expectedBehavior` | string | 对回答行为的自然语言要求 |
| `mustInclude` | string[] | 回答必须包含的内容类型 |
| `mustNotDo` | string[] | 回答禁止出现的行为 |
| `allowInsufficientEvidence` | boolean | 是否允许并期望返回证据不足 |
| `priority` | string | `P0`、`P1` 或 `P2` |
| `status` | string | `draft`、`verified`、`disabled` |

## 4. 当前分类

| 分类 | 目标 |
|---|---|
| `release-list` | 查找指定时间范围内的正式版本 |
| `release-summary` | 总结最新正式版本的重要变化 |
| `breaking-change` | 识别 Release 中明确披露的不兼容变化 |
| `capability-change` | 识别 Tool、MCP、RAG、Agent 等能力变化 |
| `upgrade-impact` | 根据 Release 证据整理升级注意事项 |
| `version-explanation` | 解释正式版、Beta 或预发布版本关系 |
| `cross-project-comparison` | 使用相同证据口径比较多个项目 |
| `release-frequency` | 比较指定时间范围内的发布次数 |
| `trend-summary` | 总结多个项目近期变化方向 |
| `judgment-boundary` | 检查主观判断时是否说明评价标准和证据边界 |
| `future-prediction` | 检查没有未来数据源时是否拒绝编造 |

## 5. 实时评测与固定评测

### 5.1 Live

当前20题均为 `live`。GitHub Release 会持续变化，因此 P0 主要检查：

- 是否识别正确项目。
- 是否调用 `github_release_list`。
- 是否传递正确 owner、repository 和时间范围。
- 是否只依据真实 Release 内容回答。
- 是否为关键事实提供 Release 链接。
- 证据不足时是否明确说明。

### 5.2 Fixture

GitHub 工具实现后，应保存固定 Release 响应到测试资源，并增加 `fixture` 评测。固定评测用于精确断言版本数、版本号、日期、事实和引用 URL，不受线上项目后续发布影响。

## 6. 结果状态

P0 使用四级结果：

| 状态 | 含义 |
|---|---|
| `PASS` | 满足预期工具、事实、引用和边界要求 |
| `PARTIAL` | 主体正确，但存在遗漏或引用不完整 |
| `FAIL` | 工具、事实、项目、时间范围或引用存在关键错误，或者编造内容 |
| `NOT_RUN` | 尚未执行 |

## 7. 核心评测维度

1. 项目识别是否正确。
2. 工具选择是否正确。
3. 工具参数是否正确。
4. 版本、日期和变化是否与 Release 一致。
5. 关键事实是否具有官方来源。
6. 引用是否真正支持相邻结论。
7. 是否遗漏时间范围内的重要正式版本。
8. 是否把预发布版本误报为正式版本。
9. 证据不足时是否明确说明。
10. 是否生成来源中不存在的信息。

## 8. 新增问题规则

新增问题前必须满足：

1. 对应首发用户的真实研究工作。
2. 明确需要哪个项目和工具。
3. 当前启用的数据源能够回答，或者明确标注应返回证据不足。
4. 写出 `expectedBehavior`、`mustInclude` 和 `mustNotDo`。
5. 通过 JSON、ID 和项目引用校验。

不要为了增加题目数量而添加含义重复的问题。
