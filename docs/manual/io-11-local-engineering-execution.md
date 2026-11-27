# IO-11 本地工程执行

先导入单模块 Maven 工程基线，保存关联该基线的研究报告。在报告的「单项任务」保存就绪的任务版本，填写目标和允许变更范围。进入「本地工程执行」，选择已登记的仓库、模型，填写本次指令和允许写入的相对路径，再点击「开始本地试改」。

指令预填冻结任务的问题、范围和完成条件。页面从任务范围中识别明确列出的源码路径；如果范围只描述业务行为，需要先填写本次允许写入的文件或目录。选择默认 Maven test 或指定 1 至 12 个测试类。定向方案请包含已有测试类，以及计划新增的回归类。

在调用模型前，服务先核对源码并在隔离环境运行原始版本。基线没有实际运行的测试时，页面显示受阻原因。基线可用后，页面显示模型的工程分析、文件变更、候选测试、实际命令与退出码。若新增了 Java 测试文件，执行器会在原实现上加载新增测试和夹具，使用与候选相同的 Maven 命令再运行一次。

结果分别显示原回归通过数、新增用例在旧实现断言失败且在候选通过的数量，以及无法区分的身份组。重复身份、异常报错、跳过或缺失原件单独说明。候选测试通过但新行为缺乏对照时，自动登记的判断仍待复核。历史运行只显示当时已记录的观察。

完成后可以查看实际补丁、下载本地运行原件。验证区的结果交接包关联固定任务版本、基线、补丁、最后两侧构建和对照摘要。下载「本地运行原件」可查看第三次对照的日志、XML、仅含新增测试的补丁及 `artifacts/regression-comparison.json`。

## 在本机启用

以 Java 21 在宿主机运行 InsightOps Server，绑定回环地址，保持现有账号认证。工作区 Owner 或系统管理员能看见本地入口。部署环境默认关闭。现有 PostgreSQL 经 Flyway 从 V51 更新到 V52，公共功能仍使用独立的原接口。

| 设置 | 值与作用 |
| --- | --- |
| `INSIGHTOPS_LOCAL_EXECUTION_ENABLED` | 首次启用设置 `true`。 |
| `INSIGHTOPS_LOCAL_EXECUTION_WORKSPACES_ROOT` | 本机登记根目录的绝对路径。将完整 Git 仓库放在 `<登记根>/<所属工作区UUID>/<仓库名>/`，仓库根目录包含 `pom.xml`。 |
| `INSIGHTOPS_LOCAL_EXECUTION_LEGACY_WORKSPACE_ID` | 旧版个人实例可填写唯一工作区 UUID，沿用登记根目录下的平铺仓库。其他工作区使用自己的 UUID 子目录。 |
| `INSIGHTOPS_LOCAL_EXECUTION_DATA_ROOT` | 与源码、缓存分开的运行目录，保留执行过程和原件。 |
| `INSIGHTOPS_LOCAL_EXECUTION_MAVEN_REPOSITORY` | 仅含依赖文件的独立 Maven 缓存目录。构建使用离线模式，缓存缺失的项目先准备依赖。 |
| `INSIGHTOPS_LOCAL_EXECUTION_MAVEN_IMAGE` | 本机预先取得的 Maven/Java 21 镜像或固定 digest。默认 `maven:3.9.11-eclipse-temurin-21`。 |
| `INSIGHTOPS_LOCAL_EXECUTION_BUILD_TIMEOUT` | 单次 Maven 构建超时，默认 `3m`。 |

OpenAI 和 DeepSeek 继续使用现有研究模型配置。模型接收任务、读取的片段和工具结果。能读的文件和能写的路径由执行器验证，试改产生在独立 Git 副本。测试只挂载该次源码快照和只读依赖缓存。运行数据库和数据目录一起保留，重启后才能下载同一运行的原件。

工程验证范围是单模块 Maven test 和普通 Surefire XML。指定类时执行器添加 `-Dtest=ClassA,ClassB`，其他 Maven 参数由产品固定。任务写入范围可覆盖 `pom.xml`、`README.md` 和选定的 `src/main`、`src/test` 文本路径。目录与全文检索支持翻页，目录支持路径筛选。模型可在一次成组调用中提交最多 8 个文件，执行器先核对每个旧哈希，再写入副本。原测试只读，本次新增的测试可继续修正。

[实现设计与完成标准](../architecture/io-11-local-engineering-execution.md)
