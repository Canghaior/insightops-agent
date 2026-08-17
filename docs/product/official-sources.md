# Alpha 官方来源登记说明

更新时间：2026-08-17

## 结论

P1.4-A 已将 Spring AI、LangChain4j 和 Dify 的官方文档接入受限采集链路，完整登记见 [tracked-projects.yaml](tracked-projects.yaml)。首次采集必须由系统管理员触发；博客、官网和 Atom 仍保持关闭。

| 项目 | 官网 | GitHub / Release | 官方文档 | 博客或公告 | 可订阅来源 | 当前启用 |
|---|---|---|---|---|---|---|
| Spring AI | Spring 项目页 | 官方仓库与 Releases | Spring AI Reference | Spring 官方博客，按 `Spring AI` 过滤 | Spring Blog Atom、GitHub Release Atom | Release API + 官方文档 |
| LangChain4j | `langchain4j.dev` | 官方仓库与 Releases | LangChain4j Documentation | Latest Release Notes；未发现独立官方博客 | GitHub Release Atom | Release API + 官方文档 |
| Dify | `dify.ai` | 官方仓库与 Releases | Dify Documentation | Dify Blog | GitHub Release Atom；未发现独立博客 RSS/Atom | Release API + 官方文档 |

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

## P1.4-A 已落实的门槛

官方文档采集链路已完成：

1. 以规范 URL 和 SHA-256 内容指纹实现文档、历史版本与切片去重。
2. 限定 HTTPS 域名/路径、校验 DNS 与重定向、遵守 robots.txt，并限制页数、深度、响应大小和请求频率。
3. 保存原文、版本、标题、语言、ETag、Last-Modified、切片和采集任务审计。
4. 失败按类型退避，系统管理员可查看状态并手动触发。

博客与 Atom 尚未启用；向量化、检索和回答注入属于 P1.4-B/C。
