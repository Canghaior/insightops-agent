# P0-004 官方来源登记验证结果

日期：2026-08-16

## 验证范围

- Spring AI、LangChain4j、Dify 三个 Alpha 项目。
- 官网、官方 GitHub 仓库、Releases、官方文档、博客/公告、RSS/Atom。
- 来源可信等级、更新频率、核验状态和 P0 采集开关。

## 结果

| 检查项 | 结果 |
|---|---|
| 项目数量与 ID | 通过：固定为 `spring-ai`、`langchain4j`、`dify` |
| 已登记来源 | 通过：共 19 条，来源 ID 全局唯一 |
| 必需来源 | 通过：每个项目均有官网、官方仓库、GitHub Release、官方文档 |
| 博客/公告 | 通过：Spring Blog、LangChain4j Latest Release Notes、Dify Blog |
| 订阅源 | 通过：三个项目均登记 GitHub Release Atom；Spring 另登记 Blog Atom |
| 缺失来源 | 通过：3 个缺口均记录 `NOT_FOUND`、说明和官方替代来源 |
| P0 范围 | 通过：仅 `github_release` 的 `collectionEnabled=true` |
| URL 与元数据 | 通过：全部来源为 HTTPS，并有可信等级、频率和核验日期 |

## 自动测试

执行命令：

```powershell
$env:INSIGHTOPS_CHAIN_GATE='true'
mvn verify
```

结果：Maven Reactor 五个模块构建成功，41 个测试全部通过。新增 `OfficialSourceRegistryTest` 通过；2 个数据库链路用例使用临时隔离 Schema 并在结束后清理。测试过程不访问 GitHub、模型或其他在线服务。

## 边界

本结果证明 P0-004 的来源登记完整且配置边界可自动检查，不代表文档、博客或 Atom 采集已实现。它们必须通过去重、重试、过滤和内容抽取验收后才能启用。
