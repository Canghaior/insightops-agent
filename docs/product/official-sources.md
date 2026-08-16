# Alpha 官方来源登记说明

更新时间：2026-08-16

## 结论

P0-004 已为 Spring AI、LangChain4j 和 Dify 建立可机器校验的官方来源登记表，完整配置见 [tracked-projects.yaml](tracked-projects.yaml)。登记来源不等于启用采集：Alpha/P0 仍只调用三个官方 GitHub 仓库的 Release API，文档、博客和 Atom 均保持关闭。

| 项目 | 官网 | GitHub / Release | 官方文档 | 博客或公告 | 可订阅来源 | P0 启用 |
|---|---|---|---|---|---|---|
| Spring AI | Spring 项目页 | 官方仓库与 Releases | Spring AI Reference | Spring 官方博客，按 `Spring AI` 过滤 | Spring Blog Atom、GitHub Release Atom | GitHub Release API |
| LangChain4j | `langchain4j.dev` | 官方仓库与 Releases | LangChain4j Documentation | Latest Release Notes；未发现独立官方博客 | GitHub Release Atom | GitHub Release API |
| Dify | `dify.ai` | 官方仓库与 Releases | Dify Documentation | Dify Blog | GitHub Release Atom；未发现独立博客 RSS/Atom | GitHub Release API |

## 可信度规则

- `T1_PROJECT_DOMAIN`：项目自有官网、官方文档站或官方博客，可作为一手事实来源。
- `T1_OFFICIAL_REPOSITORY`：项目官方 GitHub 仓库、Release API 或该仓库的 Atom，可作为代码与版本发布的一手事实来源。
- 来源缺失时只记录 `NOT_FOUND` 和替代来源，不猜测或构造 URL。
- 每条来源记录名称、URL、更新频率、可信等级、采集开关和最近核验日期；来源 ID 在全局保持唯一。

## 本次核验依据

- Spring AI 项目页会直接链接 Reference 文档；Spring 官方博客提供 Spring AI 发布公告，Spring 官方集成指南也使用 `https://spring.io/blog.atom`。
- LangChain4j 官方文档提供独立的 Latest Release Notes 页面；本次未找到项目自有的独立博客或博客 Feed。
- Dify 官网直接链接其 Documentation、Blog 和官方 GitHub；本次未找到 Dify Blog 的独立 RSS/Atom。
- 三个 GitHub 官方仓库均存在 Releases 页面，P0 使用对应 REST API，Atom 只登记不采集。

## 后续启用门槛

文档、博客或 Atom 进入采集链路前，至少需要完成：

1. 定义增量游标、内容指纹和跨来源去重规则。
2. 增加超时、限流、失败重试和来源降级策略。
3. 对 Spring 全站博客 Feed 增加 Spring AI 项目过滤。
4. 用真实样本验证发布时间、标题、正文、版本号和原始链接抽取质量。
5. 在 Alpha 报告中区分“官方事实”“模型归纳”和“待核实信息”。
