# 最终答案回归与人工复核

本入口检查**实际最终答案文本**，不会因为工具执行成功或存在来源编号就认定结论正确。它与 Run 页面“资料完整性检查”是两层能力。

## 自动规则的边界

`FinalAnswerContractEvaluator` 检查必要文本、已登记的禁止断言、来源编号是否存在，以及指定事实与来源是否在同一句/同一行对应。必要文本采用 Unicode 规范化、忽略大小写及合并空白后的包含检查。`citedFacts` 必须使用固定证据编号；每个事实单独成句，避免把多个独立来源写在同一句而无法明确对应。

这些是**必要文本与引用关联规则**，不是语义准确率。改写措辞、引用中的否定、复杂推理、未登记的新错误仍需要人工复核或语义裁判。`semanticVerified` 始终为 `false`。不能把规则通过解释为“兼容性已验证”。

## 已接入现有 RAG 评测

`RagEvaluationDataset.answerMustInclude` 现在会检查每个生成样本的最终回答；缺少任何必要片段，或引用了不存在的编号，该样本的 `answerContract.passed=false`，总体门禁失败。RAG 的来源编号是本次生成上下文中的 `[S1]…[Sn]`。返回的每题 `answerContract` 保留缺失项和引用错误，数据库以 `answer_contract` JSONB 保存。

只有被抽样生成的回答执行这项检查。`generationSampleSize=0` 仍只检查检索，不能拿其通过结果宣称生成答案已验证。历史评测的 `answerContract=null` 表示当时未检查，不会被回填成通过。已有 `citationPrecision` 和 `citationCoverage` 字段继续兼容：它们分别表示编号合法比例、被使用的证据比例，不代表事实支持率。

现有管理员接口 `POST /api/v1/admin/knowledge/evaluations` 可用：

```json
{"generationSampleSize":3,"judgeFaithfulness":true}
```

该入口会调用已配置的真实模型；此次代码验收不自动调用。`judgeFaithfulness=true` 保留原有可选语义裁判，在必要文本规则之外另行记录忠实度分数与理由。人工还应对照保存的回答与来源抽查裁判判断。默认首个 Spring AI、LangChain4j、Dify 样本增加了具体术语要求；扩充数据集时应把真正必要的事实片段写进 `answerMustInclude`，避免只要求出现 `[S`。

## Java / Vue 固定最终报告回归

`upgrade-report-contracts.json` 包含两个**明确标为合成的固定证据场景**，用于稳定检查推理边界，不声明其中内容是真实项目的发布事实。每个场景有问题、证据、必要文本、禁止断言、事实与来源对应规则、正例和反例。

反例只改变最终答案，使用相同证据，覆盖：把更晚版本修复写成目标版本已有、把另一维护分支修复视为已合入、把 Issue 已关闭当作兼容性保证、合法来源编号贴错事实、遗漏业务测试边界。正例和反例必须分别通过和失败，才能证明规则确实检查了答案。

从仓库根目录运行离线回归，不访问网络或模型：

```powershell
.\mvnw.cmd -o -pl insightops-server -am test '-Dtest=UpgradeReportEvaluationTest,RagEvaluationServiceVersionTest,RagQualityMetricsTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

首次构建须先具备项目依赖；`-o` 表示只使用本地 Maven 缓存。

## 检查人工或真实模型的新回答

1. 给被测模型每个场景的 `question` 和 `evidence`。不要提供 `referenceAnswer`、反例或预期判定，以免污染结果。可要求保留版本、资料编号与测试边界的明确措辞。
2. 保存实际最终回答为 UTF-8 JSON 数组，字段为 `caseId`、`answer`。必须包括两个场景，使用其原始 ID：`java-upgrade-boundaries` 和 `vue-upgrade-branches`。
3. 用下面的可选入口检查该文件；该入口只读已有回答，**不会自行调用模型**：

```powershell
.\mvnw.cmd -o -pl insightops-server -am test '-Dtest=UpgradeReportEvaluationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dupgrade.report.answers=D:\path\final-answers.json'
```

结果保存在 `insightops-server/target/upgrade-report-evaluation.json`。检查失败时测试会失败，结果中保留缺失文本、禁止断言和引用错配。因合理改写造成的失败需要人工判定并有依据地调整规则，不能直接删除失败样本。

人工对照原文检查：每条关键结论由哪段资料支持；目标版本与其他版本是否分清；关闭 Issue 是否被误当修复证明；推断和未验证事项是否明确；结论是否回应实际项目背景。记录模型、提示词、数据集版本与回答原文，才能比较不同实现。上述合成集通过之后，仍需补真实来源快照和真实用户案例；它不是生产质量或真实模型验收的替代品。
