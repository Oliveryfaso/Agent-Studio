package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.analyze.InstructionAnalysisEngine;
import dev.agentconfig.workbench.analyze.InstructionAnalysisReport;
import dev.agentconfig.workbench.context.EffectiveInstructionCompiler;
import dev.agentconfig.workbench.context.EffectiveInstructionContext;
import dev.agentconfig.workbench.context.ContextCompileRequest;
import dev.agentconfig.workbench.context.ContextFindingSeverity;
import dev.agentconfig.workbench.context.ContextResolutionStatus;
import dev.agentconfig.workbench.context.ProjectSemanticProfile;
import dev.agentconfig.workbench.conversion.ConversionPlan;
import dev.agentconfig.workbench.conversion.ConversionPlanner;
import dev.agentconfig.workbench.conversion.TargetConflictState;
import dev.agentconfig.workbench.conversion.TargetInventory;
import dev.agentconfig.workbench.conversion.TargetInventoryProbe;
import dev.agentconfig.workbench.conversion.ValidationStatus;
import dev.agentconfig.workbench.git.GitMetadata;
import dev.agentconfig.workbench.git.GitMetadataProbe;
import dev.agentconfig.workbench.git.GitProbeFinding;
import dev.agentconfig.workbench.git.GitProbeRequest;
import dev.agentconfig.workbench.host.HostRegistry;
import dev.agentconfig.workbench.ir.IrResolutionStatus;
import dev.agentconfig.workbench.scan.ReadOnlyWorkspaceScanner;
import dev.agentconfig.workbench.scan.ScanLimits;
import dev.agentconfig.workbench.scan.ScanResult;
import dev.agentconfig.workbench.scan.Severity;
import dev.agentconfig.workbench.skill.CodexSkillInventory;
import dev.agentconfig.workbench.skill.CodexSkillInventoryService;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;

public final class Cli {
    private final HostRegistry registry;

    public Cli(HostRegistry registry) {
        this.registry = registry;
    }

    public static Cli phaseOneDefaults() {
        return new Cli(HostRegistry.phaseOneDefaults());
    }

    public int run(String[] args, PrintWriter output, PrintWriter error) {
        if (args.length > 0 && "inspect".equals(args[0])) {
            return runInspect(args, output, error);
        }
        if (args.length > 0 && "context".equals(args[0])) {
            return runContext(args, output, error);
        }
        if (args.length > 0 && "analyze".equals(args[0])) {
            return runAnalyze(args, output, error);
        }
        if (args.length > 0 && "convert-preview".equals(args[0])) {
            return runConvertPreview(args, output, error);
        }
        if (args.length > 0 && "skill-inventory".equals(args[0])) {
            return runSkillInventory(args, output, error);
        }
        return runScan(args, output, error);
    }

    private int runSkillInventory(String[] args, PrintWriter output, PrintWriter error) {
        if (args.length != 3 || !"codex".equals(args[1])) {
            usage(error);
            return 2;
        }
        final Path root;
        try {
            root = Path.of(args[2]);
        } catch (InvalidPathException exception) {
            error.println("Invalid workspace path.");
            return 2;
        }
        try {
            CodexSkillInventory inventory = new CodexSkillInventoryService().inspect(root);
            SkillInventoryJsonWriter.write(inventory, output);
            boolean blocking = inventory.findings().stream().anyMatch(finding ->
                    finding.severity() == CodexSkillInventory.Severity.BLOCKING);
            return inventory.status() == CodexSkillInventory.Status.COMPLETE && !blocking ? 0 : 3;
        } catch (IOException exception) {
            error.println("Skill inventory failed before a report could be produced: "
                    + exception.getClass().getSimpleName());
            return 2;
        }
    }

    private int runInspect(String[] args, PrintWriter output, PrintWriter error) {
        if ((args.length != 4 && args.length != 6) || !"codex".equals(args[1])) {
            usage(error);
            return 2;
        }
        Optional<ContextCompileRequest> request = contextRequest(args, error);
        if (request.isEmpty()) {
            return 2;
        }
        try {
            EffectiveInstructionContext context = new EffectiveInstructionCompiler()
                    .compile(request.orElseThrow());
            InstructionAnalysisReport report = new InstructionAnalysisEngine().analyze(context);
            InspectionTextWriter.write(report, output);
            return report.instructionIr().resolutionStatus() == IrResolutionStatus.COMPLETE ? 0 : 3;
        } catch (IllegalArgumentException exception) {
            error.println("Inspection request rejected: " + exception.getMessage());
            return 2;
        } catch (IOException exception) {
            error.println("Inspection failed: " + exception.getClass().getSimpleName());
            return 2;
        }
    }

    private int runScan(String[] args, PrintWriter output, PrintWriter error) {
        boolean includeGitMetadata = args.length == 3 && "--git-metadata".equals(args[2]);
        if ((args.length != 2 && !includeGitMetadata) || !"scan".equals(args[0])) {
            usage(error);
            return 2;
        }
        final Path root;
        try {
            root = Path.of(args[1]);
        } catch (InvalidPathException exception) {
            error.println("Invalid workspace path.");
            return 2;
        }

        try {
            ReadOnlyWorkspaceScanner scanner = new ReadOnlyWorkspaceScanner(registry, ScanLimits.defaults());
            ScanResult result = scanner.scan(root);
            Optional<GitMetadata> gitMetadata = includeGitMetadata
                    ? Optional.of(new GitMetadataProbe().probe(GitProbeRequest.strict(root)))
                    : Optional.empty();
            ScanJsonWriter.write(result, registry, gitMetadata, output);
            boolean partial = !result.complete() || result.findings().stream()
                    .anyMatch(finding -> finding.severity() == Severity.ERROR
                            || finding.severity() == Severity.BLOCKING);
            partial = partial || gitMetadata.stream().flatMap(metadata -> metadata.findings().stream())
                    .anyMatch(finding -> finding.severity() == GitProbeFinding.Severity.ERROR
                            || finding.severity() == GitProbeFinding.Severity.BLOCKING);
            return partial ? 3 : 0;
        } catch (IOException exception) {
            error.println("Scan failed before inventory could be produced: "
                    + exception.getClass().getSimpleName());
            return 2;
        }
    }

    private int runContext(String[] args, PrintWriter output, PrintWriter error) {
        Optional<ContextCompileRequest> request = contextRequest(args, error);
        if (request.isEmpty()) {
            return 2;
        }
        try {
            EffectiveInstructionContext context = new EffectiveInstructionCompiler()
                    .compile(request.orElseThrow());
            ContextJsonWriter.write(context, output);
            boolean partial = context.resolutionStatus() == ContextResolutionStatus.PARTIAL
                    || context.findings().stream()
                            .anyMatch(finding -> finding.severity() == ContextFindingSeverity.ERROR);
            return partial ? 3 : 0;
        } catch (IllegalArgumentException exception) {
            error.println("Context request rejected: " + exception.getMessage());
            return 2;
        } catch (IOException exception) {
            error.println("Context compilation failed: " + exception.getClass().getSimpleName());
            return 2;
        }
    }

    private int runAnalyze(String[] args, PrintWriter output, PrintWriter error) {
        Optional<ContextCompileRequest> request = contextRequest(args, error);
        if (request.isEmpty()) {
            return 2;
        }
        try {
            EffectiveInstructionContext context = new EffectiveInstructionCompiler()
                    .compile(request.orElseThrow());
            InstructionAnalysisReport report = new InstructionAnalysisEngine().analyze(context);
            AnalysisJsonWriter.write(report, output);
            return report.instructionIr().resolutionStatus() == IrResolutionStatus.COMPLETE ? 0 : 3;
        } catch (IllegalArgumentException exception) {
            error.println("Analysis request rejected: " + exception.getMessage());
            return 2;
        } catch (IOException exception) {
            error.println("Analysis failed: " + exception.getClass().getSimpleName());
            return 2;
        }
    }

    private int runConvertPreview(String[] args, PrintWriter output, PrintWriter error) {
        Optional<ConversionCliRequest> cliRequest = conversionRequest(args, error);
        if (cliRequest.isEmpty()) {
            return 2;
        }
        ConversionCliRequest conversion = cliRequest.orElseThrow();
        try {
            EffectiveInstructionContext context = new EffectiveInstructionCompiler()
                    .compile(conversion.contextRequest());
            InstructionAnalysisReport report = new InstructionAnalysisEngine().analyze(context);
            if (report.instructionIr().resolutionStatus() != IrResolutionStatus.COMPLETE) {
                error.println("Conversion preview requires a complete source IR; no plan was produced.");
                return 3;
            }
            ProjectSemanticProfile source = ProjectSemanticProfile.forHost(conversion.sourceHost());
            ProjectSemanticProfile target = ProjectSemanticProfile.forHost(conversion.targetHost());
            ConversionPlanner planner = new ConversionPlanner();
            ConversionPlan firstPass = planner.plan(report.instructionIr(), source.id(), target.id(),
                    TargetInventory.unknown());
            List<String> targetPaths = firstPass.mappings().stream()
                    .flatMap(mapping -> mapping.targetCandidate().stream())
                    .map(candidate -> candidate.logicalPath()).distinct().sorted().toList();
            TargetInventory inventory = new TargetInventoryProbe()
                    .probe(conversion.contextRequest().authorizedRoot(), targetPaths);
            ConversionPlan plan = planner.plan(
                    report.instructionIr(), source.id(), target.id(), inventory);
            ConversionPlanJsonWriter.write(plan, output);
            boolean unsafeTarget = plan.mappings().stream()
                    .flatMap(mapping -> mapping.targetCandidate().stream())
                    .anyMatch(candidate -> candidate.conflictState() == TargetConflictState.INVALID_TARGET
                            || candidate.conflictState() == TargetConflictState.OUTSIDE_SCOPE
                            || candidate.conflictState()
                                    == TargetConflictState.TARGET_CHANGED_DURING_PROBE
                            || candidate.targetValidation() == ValidationStatus.FAILED
                            || candidate.semanticRoundTrip() == ValidationStatus.FAILED);
            return unsafeTarget ? 3 : 0;
        } catch (IllegalArgumentException exception) {
            error.println("Conversion preview request rejected: " + exception.getMessage());
            return 2;
        } catch (IOException exception) {
            error.println("Conversion preview failed: " + exception.getClass().getSimpleName());
            return 2;
        }
    }

    private static Optional<ContextCompileRequest> contextRequest(
            String[] args, PrintWriter error) {
        boolean baseArguments = args.length == 4;
        boolean optionalArguments = args.length == 6;
        if ((!baseArguments && !optionalArguments)
                || (!"codex".equals(args[1]) && !"claude-code".equals(args[1]))) {
            usage(error);
            return Optional.empty();
        }
        final Path root;
        final Path currentDirectory;
        final Optional<Path> codexConfigSnapshot;
        final Optional<Path> targetFile;
        try {
            root = Path.of(args[2]);
            currentDirectory = Path.of(args[3]);
            codexConfigSnapshot = optionalArguments && "codex".equals(args[1])
                    && "--codex-config".equals(args[4])
                    ? Optional.of(Path.of(args[5])) : Optional.empty();
            targetFile = optionalArguments && "claude-code".equals(args[1])
                    && "--target-file".equals(args[4])
                    ? Optional.of(Path.of(args[5])) : Optional.empty();
        } catch (InvalidPathException exception) {
            error.println("Invalid workspace or current-directory path.");
            return Optional.empty();
        }
        if (optionalArguments && codexConfigSnapshot.isEmpty() && targetFile.isEmpty()) {
            usage(error);
            return Optional.empty();
        }
        return Optional.of(new ContextCompileRequest(
                args[1], root, currentDirectory, codexConfigSnapshot, targetFile));
    }

    private static Optional<ConversionCliRequest> conversionRequest(
            String[] args, PrintWriter error) {
        boolean baseArguments = args.length == 5;
        boolean optionalArguments = args.length == 7;
        if ((!baseArguments && !optionalArguments)
                || (!"codex".equals(args[1]) && !"claude-code".equals(args[1]))
                || (!"codex".equals(args[2]) && !"claude-code".equals(args[2]))
                || args[1].equals(args[2])) {
            usage(error);
            return Optional.empty();
        }
        String[] contextArgs = optionalArguments
                ? new String[] {"context", args[1], args[3], args[4], args[5], args[6]}
                : new String[] {"context", args[1], args[3], args[4]};
        Optional<ContextCompileRequest> request = contextRequest(contextArgs, error);
        return request.map(value -> new ConversionCliRequest(args[1], args[2], value));
    }

    private record ConversionCliRequest(
            String sourceHost, String targetHost, ContextCompileRequest contextRequest) {}

    private static void usage(PrintWriter error) {
        error.println("Usage: agent-config-workbench inspect codex <authorized-workspace>"
                + " <current-directory> [--codex-config <snapshot.toml>]");
        error.println("Usage: agent-config-workbench scan <authorized-workspace> [--git-metadata]");
        error.println("   or: agent-config-workbench context codex <authorized-workspace> <current-directory>"
                + " [--codex-config <snapshot.toml>]");
        error.println("   or: agent-config-workbench context claude-code <authorized-workspace> <current-directory>"
                + " [--target-file <project-relative-file>]");
        error.println("   or: agent-config-workbench analyze codex <authorized-workspace> <current-directory>"
                + " [--codex-config <snapshot.toml>]");
        error.println("   or: agent-config-workbench analyze claude-code <authorized-workspace> <current-directory>"
                + " [--target-file <project-relative-file>]");
        error.println("   or: agent-config-workbench convert-preview <codex|claude-code>"
                + " <claude-code|codex> <authorized-workspace> <current-directory>"
                + " [source-host option]");
        error.println("   or: agent-config-workbench skill-inventory codex <authorized-workspace>");
        error.println("Commands are metadata-only: they never write to or execute content from the workspace.");
        error.println("Git administrative metadata is not inspected unless --git-metadata is supplied.");
    }
}
