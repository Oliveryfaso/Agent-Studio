package dev.agentconfig.workbench.cli;

import dev.agentconfig.workbench.git.GitMetadata;
import dev.agentconfig.workbench.git.GitProbeFinding;
import dev.agentconfig.workbench.git.GitProbeUnknown;
import dev.agentconfig.workbench.host.ArtifactType;
import dev.agentconfig.workbench.host.HostDescriptor;
import dev.agentconfig.workbench.host.HostRegistry;
import dev.agentconfig.workbench.scan.DiscoveredArtifact;
import dev.agentconfig.workbench.scan.ScanFinding;
import dev.agentconfig.workbench.scan.ScanResult;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Optional;

final class ScanJsonWriter {
    private ScanJsonWriter() {}

    static void write(ScanResult result, HostRegistry registry, PrintWriter output) {
        write(result, registry, Optional.empty(), output);
    }

    static void write(
            ScanResult result,
            HostRegistry registry,
            Optional<GitMetadata> gitMetadata,
            PrintWriter output) {
        output.println("{");
        output.printf("  \"schemaVersion\": 1,%n");
        output.printf("  \"logicalRoot\": %s,%n", json(result.logicalRoot().toString()));
        output.printf("  \"realRoot\": %s,%n", json(result.realRoot().toString()));
        output.printf("  \"scannedAt\": %s,%n", json(result.scannedAt().toString()));
        output.printf("  \"completionStatus\": %s,%n", json(result.completionStatus().name()));
        output.printf("  \"stopReason\": %s,%n", json(result.stopReason().name()));
        output.println("  \"hosts\": [");
        Iterator<HostDescriptor> hostIterator = registry.hosts().iterator();
        while (hostIterator.hasNext()) {
            HostDescriptor host = hostIterator.next();
            output.println("    {");
            output.printf("      \"id\": %s,%n", json(host.id()));
            output.printf("      \"displayName\": %s,%n", json(host.displayName()));
            output.printf("      \"roadmapTier\": %s,%n", json(host.roadmapTier().name()));
            output.printf("      \"adapterMaturity\": %s,%n", json(host.adapterMaturity().name()));
            output.printf("      \"versionStatus\": %s%n", json(host.versionStatus()));
            output.printf("    }%s%n", hostIterator.hasNext() ? "," : "");
        }
        output.println("  ],");
        if (gitMetadata.isPresent()) {
            writeGitMetadata(gitMetadata.orElseThrow(), output);
        }
        output.println("  \"artifacts\": [");
        Iterator<DiscoveredArtifact> artifactIterator = result.artifacts().iterator();
        while (artifactIterator.hasNext()) {
            DiscoveredArtifact artifact = artifactIterator.next();
            output.println("    {");
            output.printf("      \"logicalPath\": %s,%n", json(portable(artifact.logicalPath())));
            output.printf("      \"realPath\": %s,%n", json(artifact.realPath().toString()));
            output.printf("      \"hostIds\": %s,%n", stringArray(artifact.hostIds().stream().sorted().iterator()));
            output.printf("      \"artifactTypes\": %s,%n", stringArray(artifact.artifactTypes().stream()
                    .sorted(Comparator.comparing(ArtifactType::name)).map(Enum::name).iterator()));
            output.printf("      \"symbolicLink\": %s,%n", artifact.symbolicLink());
            output.printf("      \"byteSize\": %d,%n", artifact.byteSize());
            output.printf("      \"sha256\": %s,%n", json(artifact.sha256()));
            output.printf("      \"encodingHint\": %s,%n", json(artifact.encodingHint().name()));
            output.printf("      \"lineEnding\": %s%n", json(artifact.lineEnding().name()));
            output.printf("    }%s%n", artifactIterator.hasNext() ? "," : "");
        }
        output.println("  ],");
        output.println("  \"findings\": [");
        Iterator<ScanFinding> findingIterator = result.findings().iterator();
        while (findingIterator.hasNext()) {
            ScanFinding finding = findingIterator.next();
            output.println("    {");
            output.printf("      \"severity\": %s,%n", json(finding.severity().name()));
            output.printf("      \"code\": %s,%n", json(finding.code().name()));
            output.printf("      \"logicalPath\": %s,%n", json(portable(finding.logicalPath())));
            output.printf("      \"detail\": %s%n", json(finding.detail()));
            output.printf("    }%s%n", findingIterator.hasNext() ? "," : "");
        }
        output.println("  ]");
        output.println("}");
        output.flush();
    }

    private static void writeGitMetadata(GitMetadata metadata, PrintWriter output) {
        output.println("  \"gitMetadata\": {");
        output.printf("    \"isGitWorkspace\": %s,%n", metadata.isGitWorkspace());
        output.println("    \"gitDir\": {");
        output.printf("      \"kind\": %s,%n", json(metadata.gitDir().kind().name()));
        output.printf("      \"location\": %s,%n", json(metadata.gitDir().location().name()));
        output.printf("      \"path\": %s%n", metadata.gitDir().path()
                .map(path -> json(path.toString())).orElse("null"));
        output.println("    },");
        if (metadata.head().isPresent()) {
            var head = metadata.head().orElseThrow();
            output.println("    \"head\": {");
            output.printf("      \"kind\": %s,%n", json(head.kind().name()));
            output.printf("      \"value\": %s%n", json(head.value()));
            output.println("    },");
        } else {
            output.println("    \"head\": null,");
        }
        output.printf("    \"worktreeState\": %s,%n", json(metadata.worktreeState().name()));
        output.println("    \"findings\": [");
        Iterator<GitProbeFinding> findingIterator = metadata.findings().iterator();
        while (findingIterator.hasNext()) {
            GitProbeFinding finding = findingIterator.next();
            output.println("      {");
            output.printf("        \"severity\": %s,%n", json(finding.severity().name()));
            output.printf("        \"code\": %s,%n", json(finding.code().name()));
            output.printf("        \"path\": %s,%n", json(finding.path().toString()));
            output.printf("        \"detail\": %s%n", json(finding.detail()));
            output.printf("      }%s%n", findingIterator.hasNext() ? "," : "");
        }
        output.println("    ],");
        output.println("    \"unknowns\": [");
        Iterator<GitProbeUnknown> unknownIterator = metadata.unknowns().iterator();
        while (unknownIterator.hasNext()) {
            GitProbeUnknown unknown = unknownIterator.next();
            output.println("      {");
            output.printf("        \"code\": %s,%n", json(unknown.code().name()));
            output.printf("        \"detail\": %s%n", json(unknown.detail()));
            output.printf("      }%s%n", unknownIterator.hasNext() ? "," : "");
        }
        output.println("    ]");
        output.println("  },");
    }

    private static String stringArray(Iterator<String> values) {
        StringBuilder builder = new StringBuilder("[");
        while (values.hasNext()) {
            builder.append(json(values.next()));
            if (values.hasNext()) {
                builder.append(", ");
            }
        }
        return builder.append(']').toString();
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String json(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }
}
