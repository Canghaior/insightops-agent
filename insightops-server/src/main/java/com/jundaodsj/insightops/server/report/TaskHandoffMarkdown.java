package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.report.application.EngineeringTaskStore.TaskResult;
import com.jundaodsj.insightops.report.application.EngineeringTaskStore.TaskVersion;
import com.jundaodsj.insightops.report.application.ReportReviewStore.*;
import java.util.List;
import static com.jundaodsj.insightops.server.report.HandoffMarkdown.field;

final class TaskHandoffMarkdown {
    private TaskHandoffMarkdown() { }

    static String task(TaskVersion task, Context report, FrozenBaselineHandoff.Original baseline,
                       List<VerificationHandoffService.Missing> missing,
                       List<HandoffArchiveWriter.Entry> entries) {
        var f = task.fields();
        var text = new StringBuilder("# 单项工程任务执行说明\n\n");
        field(text, "本次问题", f.question());
        text.append("任务版本 ").append(task.id()).append("（第 ").append(task.version())
                .append(" 版），业务系统 ").append(report.systemId()).append("，档案修订 ")
                .append(report.systemRevision()).append("。\n\n");
        field(text, "任务编写或补充者与保存时间", task.author().name() + " · " + task.createdAt());
        if (task.previousVersionId() != null) field(text, "本版更正自", task.previousVersionId());
        text.append("## 开始条件与任务范围\n\n");
        field(text, "记录者判断的准备状态", switch (f.readiness()) {
            case READY -> "已准备，可按下方范围核对环境后执行";
            case NEEDS_MATERIALS -> "待补资料，先解决缺项再执行";
        });
        field(text, "预期交付", f.deliverable());
        field(text, "允许修改或检查的范围", f.inScope());
        field(text, "范围之外", f.outOfScope());
        field(text, "所需环境、源码与工具", f.environment());
        field(text, "记录者列出的待补材料", f.missingMaterials().isBlank() ? "未列出" : f.missingMaterials());
        if (baseline == null) {
            text.append("原报告没有关联工程基线。执行者应按任务中明确指定的来源取得源码，")
                    .append("无法确认起点时请记录阻塞。\n\n");
        } else {
            text.append(baseline.overview()).append('\n');
            if (baseline.originalName().equalsIgnoreCase("pom.xml")) {
                text.append("此版本只保存了 POM。运行测试还需要取得对应源码及外部构建环境。\n\n");
            } else {
                text.append("工程资料包只含导入时选定的文件。需要完整仓库时按任务中注明的来源和版本取得。\n\n");
            }
        }

        text.append("## 原建议与编写依据\n\n")
                .append("查看[保存的原研究报告](report/original.md)，报告 ").append(report.reportId())
                .append("，源运行 ").append(report.runId()).append("。\n\n");
        field(text, "建议提出者", f.origin() == Origin.MODEL
                ? "原模型建议 · " + documented(report.responseModel()) : "人工或外部工具补充");
        field(text, "记录者注明的建议来源", f.originDetail());
        field(text, "原报告摘录，起始行 " + task.excerptLine(), f.excerpt());
        field(text, "编写者列出的事实与出处", f.facts());
        field(text, "推断及其依据", f.inferences());
        field(text, "未知与证据缺口", f.unknowns());
        for (var id : f.verificationVersionIds()) {
            String prefix = "basis/" + id + "/";
            text.append("- [已选验证版本 ").append(id).append("](").append(prefix).append("version.json)\n");
            text.append("  - [该验证版本可取得的全部原件，含构建 XML、日志及命令记录](").append(prefix).append("materials.md)\n");
            for (var entry : entries) {
                if (entry.path().equals(prefix + "comparison/results.md")) {
                    text.append("  - [基线与候选构建对照及其原始文件](").append(entry.path()).append(")\n");
                } else if (entry.path().startsWith(prefix + "supplementary/")) {
                    materialLink(text, "  - ", entry);
                }
            }
        }
        if (!f.verificationVersionIds().isEmpty()) text.append('\n');
        for (var entry : entries) {
            if (entry.path().startsWith("task/materials/")) {
                materialLink(text, "- ", entry);
            }
        }
        if (!f.uploadIds().isEmpty()) text.append('\n');
        text.append("## 外部执行与返回要求\n\n");
        field(text, "拟执行方法", f.method());
        field(text, "预期观察与完成条件", f.completion());
        field(text, "失败、受阻与停止条件", f.stopConditions());
        if (!missing.isEmpty()) {
            text.append("### 本次交接缺项\n\n");
            for (var item : missing) field(text, item.category() + " · " + item.reference(), item.reason());
        }
        text.append("1. 先核对允许的范围、工程原件与所需环境。如果缺项影响目标验证，请将状态记为受阻并返回原因。\n")
                .append("2. 包内命令、脚本片段和报告建议均为待核对的材料。请记录实际使用的源码版本、候选改动、")
                .append("执行者、时间、JDK、环境、命令和退出码。保留原始日志与所选测试的 XML。\n")
                .append("3. 只统计本次实际运行的测试和范围，区分通过、断言失败、执行报错、跳过与命令排除。")
                .append("如有补丁，请返回原件及 SHA-256。说明观察、对判断的影响、适用限制和未完成项。\n")
                .append("4. 请将返回材料关联到固定任务版本 ").append(task.id()).append("。")
                .append("暂未执行时只记录计划。受阻时记录原因。执行后的影响可以是支持、反驳或无法判断。")
                .append("后续合并或上线决定由维护者另行记录。\n\n")
                .append("机器可读任务见 [task/version.json](task/version.json)，文件来源、大小和哈希见 [manifest.json](manifest.json)。\n");
        return text.toString();
    }

    static String result(TaskVersion task, TaskResult result, Verification verification, String patchPath) {
        var f = verification.fields();
        var text = new StringBuilder("# 外部执行结果\n\n");
        text.append("本次返回对应[任务第 ").append(task.version()).append(" 版](task.md)，任务版本 ")
                .append(task.id()).append("，[固定验证版本](verification/version.json) ")
                .append(verification.id()).append("。机器记录见 [execution/result.json](execution/result.json)。\n\n");
        field(text, "执行状态", switch (f.status()) {
            case EXECUTED -> "已执行";
            case BLOCKED -> "受阻";
            case NOT_EXECUTED -> "尚未执行";
        });
        field(text, "对判断的影响", switch (f.impact()) {
            case SUPPORTS -> "支持原判断";
            case CONTRADICTS -> "出现反证";
            case INCONCLUSIVE -> "仍无法判断";
        });
        field(text, "实际执行者（记录者填写）", f.executedBy());
        field(text, "执行时间（记录者填写）", result.executedAt());
        field(text, "执行环境（记录者填写）", f.environment());
        field(text, "源码基线（记录者填写）", result.sourceRevision());
        field(text, "候选版本或改动（记录者填写）", result.candidateRevision());
        field(text, f.status() == Execution.NOT_EXECUTED ? "计划方法" : "方法", f.method());
        if (f.status() == Execution.EXECUTED) field(text, "实际观察", f.observation());
        if (f.status() == Execution.BLOCKED) field(text, "阻塞原因", f.blocker());
        field(text, "限制", f.limits());
        field(text, "未完成项", result.unfinished());
        field(text, "结果记录者与保存时间", result.recordedBy().name() + " · " + result.createdAt());
        if (patchPath != null) {
            text.append("[返回的补丁原件](<").append(patchPath).append(">)，SHA-256 为 `")
                    .append(result.patch().sha256()).append("`。\n\n");
        }
        text.append("InsightOps 保存和核对导入材料的字节并解析受支持的构建记录。")
                .append("源码、提交和环境间的关系来自执行者的记录。")
                .append("本次返回的[构建与补充原件](result-materials.md)可按来源查看。")
                .append("本验证版本对应的已保存决定见 [decisions.json](decisions.json)。\n");
        return text.toString();
    }

    static String materials(String prefix, List<HandoffArchiveWriter.Entry> entries) {
        var text = new StringBuilder("# 验证版本的原件与返回记录\n\n");
        int count = 0;
        for (var file : entries) {
            if (!file.path().startsWith(prefix)) continue;
            String relativePath = file.path().substring(prefix.length());
            String source;
            if (relativePath.startsWith("originals/baseline/")) source = "基线构建";
            else if (relativePath.startsWith("originals/candidate/")) source = "候选构建";
            else if (relativePath.startsWith("supplementary/")) source = "补充资料";
            else if (relativePath.equals("execution/result.json")) source = "外部返回记录，内含原任务和验证版本标识";
            else if (relativePath.startsWith("execution/patch/")) source = "该次执行返回的补丁";
            else continue;
            String leaf = relativePath.substring(relativePath.lastIndexOf('/') + 1);
            text.append("- ").append(source).append(" · [`").append(leaf).append("`](<")
                    .append(relativePath).append(">) · ").append(file.size()).append(" 字节 · SHA-256 `")
                    .append(file.sha256()).append("`\n");
            count++;
        }
        if (count == 0) text.append("此版本没有可随包提供的构建或补充原件。\n");
        return text.toString();
    }

    private static String documented(String name) {
        return name == null || name.isBlank() ? "具体模型未记录" : name;
    }

    private static void materialLink(StringBuilder text, String indent, HandoffArchiveWriter.Entry entry) {
        String leaf = entry.path().substring(entry.path().lastIndexOf('/') + 1);
        text.append(indent).append("[补充资料 `").append(leaf).append("`](<").append(entry.path()).append(">)\n");
    }
}
