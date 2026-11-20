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

原始声明列保留依赖版本和属性表达式。本地值列表示从这些文件读出的字面值，或沿已核对的同仓父 POM 展开的属性。外部父 POM、导入 BOM 内容、活动 Profile、插件管理、传递依赖及有效依赖树都属于本原型之外的 Maven 行为。源码符号和测试方法行号提供走读入口，覆盖率和测试结果需要实测取得。运行时的 JVM 和真实库版本要在独立的对照实验中记录。

提取器只打开清单中的文件。单文件上限 1 MiB，拒绝 XML DTD 和重定向路径。它使用 Python 标准库，不启动 Git、Maven、网络请求、仓库脚本、插件或应用。调用方负责挑选可提供给产品的非敏感工程文件，并复核上传材料。案例所用的上传、模型运行及实验材料在独立审阅包中保存。
