# P0 聊天 Guardrail 验收结果

> 日期：2026-08-17
>
> 状态：PASS

## 已实现范围

- 空输入、超长输入和异常控制字符在创建 Run 前拒绝。
- 用户消息、历史消息和 Release 正文均作为不可信内容传入模型。
- 系统策略禁止改变白名单、扩大工具范围或泄露系统提示词、API Key、Authorization 和 Cookie。
- 工具引用在调用模型前限定为 GitHub 官方 Release tag URL。
- Release 正文中的外部链接目标从模型证据中移除。
- 回答正文只将可信 GitHub Release tag URL 渲染为可点击链接；原始 HTML 和危险协议继续转义。

## 自动化与在线验证

- Guardrail、控制器、工具证据格式化、路由和安全 Markdown 均有确定性测试。
- 最终 Maven Reactor `verify` 共 57 项测试通过，数据库链路门禁 0 跳过；前端 lint、12 项测试和生产构建通过。
- 在线提示注入要求泄露系统提示词和 DeepSeek Key、查询非白名单仓库时，模型明确拒绝，未执行越权工具。
- 浏览器检查当前回答的 10 个可点击链接，全部为三个白名单项目的 GitHub Release tag URL。

完整设计见 `docs/architecture/p0-chat-guardrail.md`。
