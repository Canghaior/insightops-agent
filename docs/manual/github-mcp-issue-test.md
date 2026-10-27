# GitHub 官方 MCP Issue 工具本地验收

本次新增 `github_issue_read`，用于从 GitHub 官方 MCP 读取当前 Workspace 已启用项目的指定 Issue。现有 Release 查询和知识库检索继续使用原有实现。

## 1. 配置本机授权

PAT（Personal Access Token）是供程序访问 GitHub 的授权凭据。已有合适 Token 时，可以直接进入下面的本机填写步骤。

1. 登录 GitHub，打开[创建细粒度 Token](https://github.com/settings/personal-access-tokens/new)。
2. Token name 填写 `InsightOps-local`；Expiration 选择 `30 days`；Resource owner 保留自己的账号。
3. Repository access 保留 `Public repositories`。这次只读取公开 Issue，不添加写权限。GitHub 的细粒度 Token 默认包含所有公开仓库的只读访问，详见[官方创建说明](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-fine-grained-personal-access-token)。
4. 点击 Generate token，复制生成的 Token。

在本机 PowerShell 中分行执行：

```powershell
cd D:\AGENT-JUNDAO\1-Agent-LJW
.\scripts\configure-github-mcp.ps1
```

看到 `GitHub PAT` 提示后，粘贴 Token 并按 Enter。输入内容会隐藏；脚本只更新项目 `.env` 中的 `GITHUB_MCP_ENABLED` 和 `GITHUB_MCP_TOKEN`，不会打印 Token。保存成功表示配置已写入，Token 是否有效仍需后续实际查询确认。

也可以手工编辑项目根目录 `.env`，添加或更新以下配置。同名配置只保留一条。真实 Token 只填写在本机，不要贴到聊天、截图或 Git 提交中。

```dotenv
GITHUB_MCP_ENABLED=true
GITHUB_MCP_TOKEN=在本机填写你的GitHubToken
```

保存后，使用包含本次改动的后端构建并重启 Server，使配置生效。前端页面地址为 `http://127.0.0.1:15173/`。

登录后打开[当前可用工具目录](http://127.0.0.1:15173/api/v1/agent/tools)，在返回的 JSON 中查找 `github_issue_read`。出现该工具表示服务端已加载启用配置，不代表已经通过 GitHub 认证或联网查询。

如果开关关闭或 Token 为空，工具不会提供给模型，也不会出现在可用工具目录；此时未必会出现“尚未配置授权”的聊天错误提示。请先检查配置并重启后端，再开始问答测试。

「Agent 工具与 MCP」页面下方管理的是通用 MCP 连接；这次专用 GitHub Issue 工具由服务端配置管理，不会在该列表中新增一条连接，也无需在该页面重复填写官方服务地址。

## 2. 选择一个真实 Issue

1. 登录本地 InsightOps，确认「跟踪项目」中目标项目已启用。
2. 打开该仓库在 GitHub 上的 Issues 页面，选一个能在浏览器中查看的 Issue，记下编号和链接。不要用 Pull Request 编号替代。
3. 在「研究问答」中新建会话，替换下面模板中的仓库名称和编号后发送：

```text
请使用 GitHub Issue MCP 工具，读取 <owner/repo> 的 Issue #<编号>。
请列出标题、当前状态和问题正文摘要，并引用该 Issue 的官方链接。
如果工具调用失败，请明确说明，不要根据模型记忆补充。
```

输入一个明确的仓库和 Issue 编号即可；连接地址和 Token 不属于提问参数。

## 3. 检查界面和执行记录

- 工具区域应出现「GitHub Issue · MCP」，并成功返回结果。
- 回答应包含该 Issue 的标题、状态和官方链接；打开引用，与浏览器中的 Issue 核对。
- 打开本次 Run 的执行记录，确认工具名称是 `github_issue_read`，参数包含 `projectId` 和 `issueNumber`。
- 该工具读取指定 Issue 正文，不代表已经读取评论或搜索了所有 Issue。需要评论或搜索时，后续另行扩展工具。

如果没有出现该工具，只得到普通聊天回答，不能将本次问答视为 MCP 验收通过。

## 4. 回归原有工具

在新会话分别发送：

```text
请使用 GitHub Release 工具查询 Spring AI 最近的正式版本，列出版本号和官方链接。
```

```text
请只根据本地知识库解释 Spring AI ChatClient 如何发起简单对话，并引用资料。
```

预期分别调用原有 `github_release_list` 和 `knowledge_hybrid_search`，能够正常回答并展示引用。

## 常见失败提示

| 提示 | 下一步 |
| --- | --- |
| 尚未配置授权或工具尚未启用 | 检查 `.env` 配置，确认保存后已重启后端。 |
| 授权失败 | 检查 Token 有效期和仓库读取权限，不要将 Token 发到聊天中。 |
| 没有找到 Issue 或无权读取 | 核对仓库、Issue 编号和 GitHub 账号权限。 |
| 暂时无法连接 | 检查本机到 GitHub 官方 MCP 的网络连通性后重试。 |
| 返回的数据无法识别 | 保留 Run ID 和错误码，交由开发排查，不把错误视为查询成功。 |
