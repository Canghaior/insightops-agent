# IO-04A · 只读 Maven 工程资料提取

本原型用于 Spring 官方单体 Petclinic 的公开工程基准。输入是 `4a021e4ccf7a48c8d1b5a68485418a49e1fed606` 中的 9 个指定文件，关注 Spring Boot 父 POM 从 3.4.2 升至 3.5.0 后，H2 上的主人姓氏查询和详情展示。工程人员运行独立脚本，产品用户通过现有「知识文件」上传资料，在「系统决策评估」选择文件。

## 使用

在仓库根目录运行。下面的固定快照目录需要包含清单列举的文件，并保留它们相对于项目根的路径。输出目录应当尚不存在，且位于工程目录之外。

```powershell
py -3.12 scripts/maven_case_extract.py --project D:/evidence/fixed-snapshot --scope scripts/cases/io04a-petclinic.json --output D:/evidence/petclinic-extract-01
py -3.12 -m unittest discover -s scripts/tests -p test_maven_case_extract.py -v
```

- `manifest.json` 记录请求的基线标签、实际读取的文件哈希、各文件读取状态、事实所在行，以及展开属性时使用的本地定义。标签由调用方提供。比较 Git 对象或保留固定文件快照，才能证明调用方给出的提交与文件内容一致。
- `工程事实.md` 可原样上传。F01 等标识指向文件索引，L 后的数字是从 1 开始的源码行号。外部项目说明、维护提议和上游文档另存为资料，保留作者、适用版本、来源和取得日期。
- 本原型以白名单读取根和模块 POM、指定 Java 类和 Maven Wrapper 的版本声明。若启用案例配置提取，仅输出 application.properties 中指定的 H2、SQL 初始化及 JPA 配置项和可识别值。本地父 POM 核对坐标后才能展开属性。遇到未读、重定向、格式不支持和缺失资料时，应先看清单中的状态和未知项。

## 结果的解释

原始声明列保留依赖版本和属性表达式。本地值可以来自当前 POM 内的简单展开，也可以来自坐标已经核对的父 POM 中的字面值。如果从父 POM 继承的属性自身还引用其他属性，子模块可能覆盖其定义。此时输出保留原表达式与父声明行号，并在未知项中列明使用它的模块。外部父 POM、导入 BOM 内容、活动 Profile、插件管理、传递依赖及有效依赖树都属于本原型之外的 Maven 行为。源码符号和测试方法行号提供走读入口，覆盖率和测试结果需要实测取得。运行时的 JVM 和真实库版本要在独立的对照实验中记录。

提取器只打开清单中的文件。单文件上限 1 MiB，拒绝 XML DTD 和重定向路径。它使用 Python 标准库，不启动 Git、Maven、网络请求、仓库脚本、插件或应用。调用方负责挑选可提供给产品的非敏感工程文件，并复核上传材料。案例所用的上传、模型运行及实验材料在独立审阅包中保存。

## 回接已经执行的 Surefire 回归

保留每次构建当时的 `target/surefire-reports/TEST-*.xml`、构建命令与 Maven 日志。每次只给工具一个构建的目录。命令记录是带有 `command`（实际参数数组）和 `exit`（实际退出码）的 UTF-8 JSON 文件。输出路径位于原始资料目录之外，父目录应已存在。

```powershell
py -3.12 scripts/surefire_evidence.py --reports D:/evidence/old/surefire-reports --build-record D:/evidence/old/build-1.json --output D:/evidence/old-results.md
py -3.12 -m unittest discover -s scripts/tests -p test_surefire_evidence.py -v
```

转换器核对每份 XML 的套件计数，列出方法、通过、断言失败、执行报错、跳过及异常正文。文件名、SHA-256 和 testcase 序号可定位原件。形如 `-Dtest=!ClassA,!ClassB` 的精确排除单列在命令范围内。对于其他选择表达式、缺席的方法或没有原件的构建，实际状态仍待取得。转换器只支持单套件的普通 Surefire XML。遇到重跑标记、矛盾结果或总数不符时会报错。

工程人员用 Maven 总结行核对整目录是否齐全，再将结果与固定源码、补丁、命令和实际行为关联。通过「知识文件」上传审阅后的资料，打开原报告的「验证记录与人工决定」，在已有记录上选择「补充或更正」并勾选附件。审阅者可在新版本下载资料，点「查看被更正的第 1 版」读回原始记录。
