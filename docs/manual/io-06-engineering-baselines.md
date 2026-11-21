# IO-06 Maven 工程基线

IO-05 已通过 ChatGPT 审阅。IO-06 从本地 `66e802f860c2922b6a0abf01519c2a8f8b27600c` 继续。验收使用独立数据库与端口，保留旧验收环境和原有未跟踪文件。

| 单元 | 接口与完成标准 | 状态 |
| --- | --- | --- |
| A　输入格式 | 原始单模块 pom.xml，或 `insightops.maven-baseline/v1` ZIP；旧提取清单及白名单文件在副本中打包，来源按路径、行号和 SHA 核对。 | 实施中 |
| B　工程基线 | `/api/v1/business-systems/{id}/engineering-baselines` 下的 preview、保存、列表、详情及原件下载。服务端解析并核对预览指纹，保存按系统和所有者编号，重放同一请求不会产生重复版本。 | 待验证 |
| C　评估选择 | 决策/升级评估各接收可选 engineeringBaselineId。工作流输入及实际研究提示来自服务端冻结的同一快照。再次研究展示旧选择，主动更换才使用新版本。 | 待验证 |
| D　报告与交接 | 运行及报告从当时的输入渲染事实、位置和缺口。固定验证版本的交接包使用报告冻结的基线，旧报告不追补。 | 待验证 |

用户可直接选一个根 POM。需要导入多份工程材料时，在授权源码副本外执行 `python scripts/maven_baseline_pack.py --project <单模块根> --scope <范围.json> --output <新包.zip>`。它复用只读提取器。已有 `manifest.json` 可通过 `--legacy-manifest` 原样带入，需与输入源码字节及只读重算的结果相符。支持可选的 `--dependency-tree`、`--maven-version`、`--application-log`。这些参数指向已有记录，打包与产品均不运行 Maven 或项目代码。

包内 baseline.json 列出格式、调用方基线标签、关注坐标和每件原件的 SHA-256；sources/ 保留白名单工程相对路径；evidence/ 保存提取结果及选定的既有记录。格式、数量、大小、目录、重复路径和哈希在服务端核对。提取器旧版完整清单可供下载。产品的事实快照仅包括本版能核对的种类，不能将上传者的任意 JSON 作为产品已核验的工程事实。

案例使用已审 Petclinic 固定源码与 Spring 官方 REST 指南的独立 complete 模块。分别记录 Maven 声明、静态属性展开、外部 Maven 解析记录、构建工具 JDK、应用进程 JDK、用户说明与未知项。业务档案和工程原件分开保存。单次评估只接收明确选择的基线中的结构化事实与来源，没有自动采集或嵌入工程原件。
