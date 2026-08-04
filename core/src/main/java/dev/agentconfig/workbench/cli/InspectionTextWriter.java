package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.analyze.AnalysisCertainty;
import dev.agentconfig.workbench.analyze.AnalysisFinding;
import dev.agentconfig.workbench.analyze.AnalysisFindingType;
import dev.agentconfig.workbench.analyze.AnalysisNotice;
import dev.agentconfig.workbench.analyze.AnalysisReference;
import dev.agentconfig.workbench.analyze.InstructionAnalysisReport;
import dev.agentconfig.workbench.ir.InstructionSource;
import dev.agentconfig.workbench.ir.InstructionSourceState;
import dev.agentconfig.workbench.ir.IrResolutionStatus;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a content-free, human-readable summary for the Codex-first inspection flow. */
final class InspectionTextWriter {
    private InspectionTextWriter() {}

    static void write(InstructionAnalysisReport report, PrintWriter output) {
        Map<String, InstructionSource> sourcesById = new LinkedHashMap<>();
        report.instructionIr().sources().forEach(
                source -> sourcesById.put(source.identity().sourceId(), source));

        output.println("Agent Config Workbench — Codex 项目检查");
        output.printf("检查状态：%s%n", report.instructionIr().resolutionStatus()
                == IrResolutionStatus.COMPLETE ? "完整" : "未完整完成，请查看下方限制");
        output.println("安全说明：本次检查没有修改项目文件，也没有执行项目内容。");
        output.println();

        writeEffectiveInstructions(report, output);
        writeFindings(report, sourcesById, output);
        writeLimits(report, sourcesById, output);
        writeNextStep(report, output);
        output.flush();
    }

    private static void writeEffectiveInstructions(
            InstructionAnalysisReport report, PrintWriter output) {
        List<InstructionSource> active = report.instructionIr().sources().stream()
                .filter(source -> source.state().participatesInLoadOrder())
                .sorted((left, right) -> Integer.compare(left.loadOrder(), right.loadOrder()))
                .toList();
        List<InstructionSource> inactive = report.instructionIr().sources().stream()
                .filter(source -> !source.state().participatesInLoadOrder())
                .toList();

        output.println("当前生效的项目指令");
        if (active.isEmpty()) {
            output.println("- 当前目录没有生效的 Codex 项目指令文件。");
        } else {
            for (int index = 0; index < active.size(); index++) {
                InstructionSource source = active.get(index);
                output.printf("%d. %s — %s%n", index + 1, source.logicalPath(), stateLabel(source));
            }
        }
        if (!inactive.isEmpty()) {
            output.println("未加载的项目指令");
            inactive.forEach(source -> output.printf("- %s — %s%n",
                    source.logicalPath(), stateLabel(source)));
        }
        output.println();
    }

    private static String stateLabel(InstructionSource source) {
        return switch (source.state()) {
            case ACTIVE -> String.format("已加载（%d bytes）", source.includedBytes());
            case ACTIVE_TRUNCATED -> String.format(
                    "仅加载部分内容（%d/%d bytes）", source.includedBytes(), source.byteSize());
            case INACTIVE -> "对当前目录不生效";
            case SHADOWED -> "已被更具体的指令文件覆盖";
            case MISSING -> "已引用但文件缺失";
            case INVALID -> "无法安全解析";
            case NOT_EVALUATED -> "无法确认是否生效";
        };
    }

    private static void writeFindings(
            InstructionAnalysisReport report,
            Map<String, InstructionSource> sourcesById,
            PrintWriter output) {
        List<AnalysisFinding> confirmed = report.findings().stream()
                .filter(finding -> finding.certainty() == AnalysisCertainty.DETERMINISTIC)
                .toList();
        List<AnalysisFinding> suggestions = report.findings().stream()
                .filter(finding -> finding.certainty() == AnalysisCertainty.HEURISTIC_CANDIDATE)
                .toList();

        output.println("检查结果");
        if (confirmed.isEmpty() && suggestions.isEmpty()) {
            if (report.instructionIr().resolutionStatus() == IrResolutionStatus.COMPLETE) {
                output.println("- 未发现重复内容或冲突候选。");
            } else {
                output.println("- 已检查范围内没有形成可报告的问题；该结论不覆盖未检查内容。");
            }
        }
        if (!confirmed.isEmpty()) {
            output.println("确定问题");
            confirmed.forEach(finding -> output.printf("- %s: %s%n",
                    findingLabel(finding.type()), references(finding, sourcesById)));
        }
        if (!suggestions.isEmpty()) {
            output.println("需要确认（启发式建议，不会自动修改）");
            suggestions.forEach(finding -> output.printf("- %s: %s%n",
                    findingLabel(finding.type()), references(finding, sourcesById)));
        }
        output.println();
    }

    private static String findingLabel(AnalysisFindingType type) {
        return switch (type) {
            case EXACT_EFFECTIVE_DUPLICATE -> "相同的有效指令内容被重复加载";
            case NORMALIZED_DIRECTIVE_DUPLICATE -> "这些规则可能表达了相同要求";
            case DIRECT_POLARITY_CONFLICT -> "这些规则可能互相冲突";
        };
    }

    private static String references(
            AnalysisFinding finding, Map<String, InstructionSource> sourcesById) {
        return finding.references().stream()
                .map(reference -> referenceLabel(reference, sourcesById))
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("可在 JSON 报告中查看技术证据");
    }

    private static String referenceLabel(
            AnalysisReference reference, Map<String, InstructionSource> sourcesById) {
        InstructionSource source = sourcesById.get(reference.sourceId());
        String path = source == null ? "未知指令文件" : source.logicalPath();
        return reference.line() > 0 ? path + ":" + reference.line() : path;
    }

    private static void writeLimits(
            InstructionAnalysisReport report,
            Map<String, InstructionSource> sourcesById,
            PrintWriter output) {
        if (report.notices().isEmpty() && report.instructionIr().limitations().isEmpty()) {
            return;
        }
        output.println("检查限制");
        report.notices().forEach(notice -> output.printf("- %s%s%n",
                noticeLabel(notice), noticeLocation(notice, sourcesById)));
        if (report.instructionIr().resolutionStatus() != IrResolutionStatus.COMPLETE
                && report.notices().isEmpty()) {
            output.println("- 部分 Codex 加载行为无法安全确认。");
        }
        output.println();
    }

    private static String noticeLabel(AnalysisNotice notice) {
        return switch (notice.code()) {
            case "EFFECTIVE_PAYLOAD_ANALYSIS_LIMIT" ->
                    "文件超过了本次有界内容分析的预算";
            case "DIRECTIVE_UTF8_REQUIRED" ->
                    "文件不是有效 UTF-8，因此跳过了规则级检查";
            case "LINE_LIMIT_REACHED" ->
                    "仅检查了该文件有界范围内的规则";
            case "ITEM_TOO_LONG" ->
                    "规则级检查跳过了一条异常长的列表项";
            case "DIRECTIVE_LIMIT_REACHED" ->
                    "已达到单次检查的规则数量上限";
            case "INPUT_TRUNCATED" ->
                    "仅检查了该文件的有界前缀";
            default -> "有界分析未能检查全部内容";
        };
    }

    private static String noticeLocation(
            AnalysisNotice notice, Map<String, InstructionSource> sourcesById) {
        InstructionSource source = sourcesById.get(notice.sourceId());
        if (source == null) {
            return "";
        }
        return notice.line() > 0
                ? " (" + source.logicalPath() + ":" + notice.line() + ")"
                : " (" + source.logicalPath() + ")";
    }

    private static void writeNextStep(InstructionAnalysisReport report, PrintWriter output) {
        output.println("建议下一步");
        if (report.instructionIr().resolutionStatus() != IrResolutionStatus.COMPLETE) {
            output.println("- 先处理检查限制，再把结果用于转换或编辑。");
        } else if (report.summary().deterministicFindingCount() > 0) {
            output.println("- 整理指令文件前，先检查已确认的重复内容。");
        } else if (report.summary().heuristicFindingCount() > 0) {
            output.println("- 结合上下文检查这些建议；它们不能作为安全的自动修改依据。");
        } else {
            output.println("- 当前没有需要立即处理的项目指令问题。");
        }
        output.println("- 如需机器可读的技术证据，请运行 scripts/run-analyze.sh。");
    }
}
