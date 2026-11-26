# IO-05 工程证据导入、版本对照与验证交接

起点为 `c5eec0895f62f38ba95d6c06fe3fc2f157bb8355`。开发分支为 `feature/io-05-build-evidence`。用户原有的未跟踪前端配置说明单独保留。案例沿用公开 Petclinic 的两次历史构建。IO-05 回放时间、原测试时间和执行者分别登记。

| 单元 | 完成依据 | 当前进度 |
| --- | --- | --- |
| A 旧工具与状态订正 | 混合大小写排序回归，两份 Markdown 与归档字节相同，进程状态有观察时间和来源 | 已在 Windows 与 Linux 容器各通过 5 项测试，两份归档 Markdown 均逐字节相同。已核对进程与监听端口。 |
| B 批次导入 | 原件、哈希、可查定位，实际鉴权下载，缺失与不支持情况可见，独立于知识库 | Chrome 已实际导入两批，每批 16 份 XML、Maven 日志、命令记录和独立的排除方法清单；38 件原件全部下载逐字节比对。 |
| C 版本对照 | 以身份及出现次数比较，可追溯两处报错，区分排除、跳过、范围不明和构建结果 | 54 组测试身份一一对应，2 项变为执行报错。界面可展开原堆栈、打开原 XML。两个排除类的 4 个方法另有带行号的清单。 |
| D 验证连接 | 原报告下追加确切版本，保留旧附件、丢响应重试和更正规则，可核对三种来源 | 已在隔离副本中把对照追加为第 3 版，保留第 2 版的四份补充资料。重新登录后读回，报告、输入、回答和前两版与归档相同。 |
| E 交接 ZIP | 从指定版本导出原报告、原件、差异、清单、限制、补充证据和对应决定 | 浏览器已分别下载第 2、3 版。独立解包核对了所有清单项。第 2 版明确标明当时尚无构建批次。 |
| 条件扩展 | 核心闭环通过后验证已关联系统的升级报告，完善所触及页面的引导 | 已通过第二个隔离库的真实 Chrome 验收。原有已知版本升级报告可记录并交接，可信旧模板双侧缺类型仍可访问，无系统关联的报告隐藏入口且接口拒绝。报告、验证和知识资料的导航文案已复核。 |
| 交付 | 隔离数据库、真实 Chrome、历史与哈希不变量、完整补丁、产品包及开发审阅包 | 已在最终制品上独立重做整条验收。代码增量、交接 ZIP、原始日志、截图和限制收录于本机 IO-05 审阅包。 |

恢复入口为本文件和工作区的 `local-validation/IO-05`。IO-04B 的四份已上传附件、32 份原 XML 与原审阅 ZIP 作为只读对照。工程证据仅作归档和确定性解析。使用者提供已有测试输出，无需再次执行 Maven 或发起模型请求。

IO-04B 旧核对文件中的 `temporaryCollectorStopped=true` 是执行方记录的固定值。该字段自身没有观测 PID 和端口，不能证明当时状态。后续核查使用 `scripts/observe-local-listener.ps1`，同时记录 Windows 进程、监听 PID 及观测时间。IO-05 开工检查中，历史临时 PID 43148 与端口 19964 均缺席，原 Worker 的 PID 36192 正在监听 19959。这只描述本次观测窗口。历史签封包保留，活动核查脚本另写带时间的回执。

## 使用与核对

1. 在「报告与验证」打开已关联业务系统的决策报告或已知版本升级报告。进入「构建证据与对照」，分别导入基线和候选的普通 Surefire XML，可附 Maven 日志、命令 JSON 和带源码位置的排除方法清单。

   在执行模块的 `target/surefire-reports` 目录选择原名为 `TEST-*.xml` 的报告。命令 JSON 同时支持 `exit`、`start`、`end` 和 `exitCode`、`startedAt`、`endedAt` 两类字段。两种写法都存在时须表达同一个退出码或时间。产品按原字节保存文件。
2. 点击「对照两次构建」。逐项查看测试身份、出现次数、状态变更和原始堆栈。构建的退出码、日志、XML 和操作者填写的来源各有标记。
3. 选择新建验证或补充已有版本，核对原观察、执行人和限制后保存。用对应版本旁的「下载第 N 版验证交接包」交接。旧版只含它当时引用的资料。资料不可取得或未上传时，导出说明列出缺项。

单模块每批最多 40 份 Surefire XML、3 份可选材料、4,000 个 testcase，每份上限 2 MiB，每批上限 12 MiB。导入接收已有构建产物，工程资料独立于知识检索。重跑和 flaky 结果仍需按原执行记录单独核对。

## Maven 多模块证据

1. 在基线和候选各自的导入表单勾选「Maven 多模块构建」。登记实际构建涉及的模块，根模块使用 `.`，子模块使用仓库根相对路径。
2. 填写 Maven Reactor Summary 中对应的项目名，将 `TEST-*.xml` 分配到各模块。不同模块可以有同名报告。未获得 XML 的模块也可登记。
3. 上传这次构建的 Reactor 日志和命令记录。选择范围由提交者声明，只有可按项目名唯一匹配的日志才能提供模块状态。缺少对应 XML 时保留缺项。
4. 打开构建对照核对模块状态、测试身份以及两侧原始证据。将对照附到保存的验证版本后下载交接包，`comparison/diff.json` 和对照说明含模块信息。

API 沿用报告构建导入接口。`metadata.modules` 按顺序包含 `path`、`projectName` 和可选的 `selected`。每个模块的 XML 用同序号的文件字段提交，从 `moduleXml0` 开始。单模块调用沿用 `xmlFiles`。多模块每批最多登记 32 个模块、160 份 XML，沿用总字节和 testcase 上限。Reactor 日志支持 UTF-8、GB18030 及兼容的 GBK 原件。该公开工程回放没有客户效率或付费比较基线。

最终 Petclinic 环境为 `http://127.0.0.1:20057`。报告 `af22050f-6d5b-4a76-9247-8742947555e6` 的第 3 版验证为 `c4b7ebdf-41d2-4391-8136-417e6331713c`。独立版本升级扩展环境为 `http://127.0.0.1:20067`。两者只监听本机。


## 采集联合构建及重跑证据

在本机已有 Maven JDK 21 镜像和只读 Maven 缓存的环境中，固定经授权仓库的提交后执行以下命令。

```powershell
python scripts/maven_reactor_evidence.py --repo <Git仓库> --revision <提交> --offline-repository <依赖缓存> --output <新目录> --attempts 2
```

脚本为每次执行导出独立 Git 快照，在无网络的本机 Docker 容器中跑完整 Reactor 的 `mvn test`。普通模块通过固定 POM 的静态声明定位。动态 Profile 模块需另行核对。`attempts.json` 保留每一轮退出码、截断和收集状态。每个 `attempt-N` 目录中有命令、Maven 日志、模块清单、XML 及文件哈希。无唯一对应关系的重跑观察保留在各轮原件中。

在报告中导入某一轮时，使用其 `manifest.json` 的模块分组选择原件。不同尝试分别保存，测试结果以每轮实际 XML 为准。`matchedTestsWithDifferentObservations` 仅标出同提交、同命令下各轮都唯一出现且状态有变化的用例。

## 关联 GitHub CI 和 PR 审阅材料

`scripts/github_upgrade_review.py` 将 GitHub 仓库、基线分支和候选提交绑定到一份交付记录。指定本地 origin 对应的仓库、两侧引用、分支名和一个新输出目录。可用 `--attempts` 关联同一候选提交的 Maven 实跑清单。

```powershell
python scripts/github_upgrade_review.py --repo <检出目录> --repository <owner/repo> --base-ref <基线> --candidate <候选> --base-branch <基线分支> --head-branch <候选分支> --output <新目录>
```

输出包含精确共同祖先的 Git 差异、改变的文件、候选 SHA 的 GitHub Actions 结果和草稿 PR 文本。CI 可用时仅保留相同 SHA 的记录，权限不足或尚未运行时显示实际状态。`--local-only` 适合先生成本机材料。

实际创建草稿需使用 `--open-draft` 及已授权的 `INSIGHTOPS_GITHUB_TOKEN` 或 `GITHUB_TOKEN`。工具核对远端分支仍指向所选提交，重复执行时读取同一分支组合的已存在 PR。项目按已有规则审阅和合并。

## 本机多仓库验证批次

`scripts/upgrade_batch.py` 从 JSON 项目清单固定每个仓库的提交，按项目保存独立运行状态和证据。示例中的 `revision` 使用对应仓库的完整提交 SHA。

```json
{
  "projects": [
    {"id": "billing", "repo": "D:/repos/billing", "revision": "<完整提交 SHA>", "approved": true, "attempts": 2},
    {"id": "orders", "repo": "D:/repos/orders", "revision": "<完整提交 SHA>", "approved": false}
  ]
}
```

先用 `python scripts/upgrade_batch.py --plan <清单> --output <新目录>` 预览。实际执行时指定 `--execute --offline-repository <缓存>`。获批准的项目使用离线 Reactor 执行器。每做完一个项目更新 `batch-status.json`，保留失败、超时、证据不足和等待批准的项目。需要遇到构建失败便暂停后续项目时启用 `--stop-on-failure`。
