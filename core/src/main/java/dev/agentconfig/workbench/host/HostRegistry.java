package dev.agentconfig.workbench.host;

import static dev.agentconfig.workbench.host.ArtifactType.AGENT;
import static dev.agentconfig.workbench.host.ArtifactType.HOOK;
import static dev.agentconfig.workbench.host.ArtifactType.INSTRUCTION;
import static dev.agentconfig.workbench.host.ArtifactType.PLUGIN;
import static dev.agentconfig.workbench.host.ArtifactType.POLICY;
import static dev.agentconfig.workbench.host.ArtifactType.RUNTIME_CONFIG;
import static dev.agentconfig.workbench.host.ArtifactType.SKILL;
import static dev.agentconfig.workbench.host.ArtifactType.WORKFLOW;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class HostRegistry {
    private static final LocalDate EVIDENCE_DATE = LocalDate.of(2026, 8, 3);

    private final Map<String, HostAdapter> adapters;
    private final Set<String> configurationDirectoryNames;

    public HostRegistry(Collection<? extends HostAdapter> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        Map<String, HostAdapter> byId = new LinkedHashMap<>();
        Set<String> directoryNames = new LinkedHashSet<>();
        candidates.stream()
                .sorted(Comparator.comparing(adapter -> adapter.descriptor().id()))
                .forEach(adapter -> {
                    Objects.requireNonNull(adapter, "adapter");
                    if (adapter.apiVersion() != HostAdapter.API_VERSION) {
                        throw new IllegalArgumentException("Unsupported adapter API version");
                    }
                    HostDescriptor descriptor = adapter.descriptor();
                    if (byId.putIfAbsent(descriptor.id(), adapter) != null) {
                        throw new IllegalArgumentException("Duplicate host id: " + descriptor.id());
                    }
                    descriptor.discoveryRules().stream()
                            .map(DiscoveryRule::anchorRoot)
                            .filter(Objects::nonNull)
                            .forEach(directoryNames::add);
                });
        if (byId.isEmpty()) {
            throw new IllegalArgumentException("Host registry must not be empty");
        }
        this.adapters = Map.copyOf(byId);
        this.configurationDirectoryNames = Set.copyOf(directoryNames);
    }

    public static HostRegistry phaseOneDefaults() {
        return new HostRegistry(List.of(
                ManifestHostAdapter.phaseOne(codex()),
                ManifestHostAdapter.phaseOne(claude()),
                ManifestHostAdapter.phaseOne(cursor()),
                ManifestHostAdapter.phaseOne(copilot()),
                ManifestHostAdapter.phaseOne(windsurfDevin())));
    }

    public Collection<HostDescriptor> hosts() {
        return adapters.values().stream().map(HostAdapter::descriptor)
                .sorted(Comparator.comparing(HostDescriptor::id)).toList();
    }

    public Optional<HostDescriptor> find(String hostId) {
        return Optional.ofNullable(adapters.get(hostId)).map(HostAdapter::descriptor);
    }

    public boolean supports(String hostId, AdapterMaturity required) {
        return find(hostId).map(HostDescriptor::adapterMaturity)
                .map(tier -> tier.includes(required)).orElse(false);
    }

    public List<HostMatch> match(Path relativePath, boolean directory) {
        List<HostMatch> matches = new ArrayList<>();
        for (HostDescriptor host : hosts()) {
            Set<ArtifactType> types = EnumSet.noneOf(ArtifactType.class);
            host.discoveryRules().stream()
                    .filter(rule -> rule.matches(relativePath, directory))
                    .map(DiscoveryRule::artifactType)
                    .forEach(types::add);
            if (!types.isEmpty()) {
                matches.add(new HostMatch(host.id(), types));
            }
        }
        return List.copyOf(matches);
    }

    /** Avoids traversing or silently accepting symlinked host-configuration directories. */
    public boolean isKnownConfigurationDirectory(Path relativePath) {
        for (Path segment : relativePath) {
            if (configurationDirectoryNames.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static HostDescriptor codex() {
        return new HostDescriptor(
                "codex",
                "OpenAI Codex",
                RoadmapTier.CORE,
                AdapterMaturity.INVENTORY,
                "unverified-phase1",
                EnumSet.of(Capability.INSTRUCTIONS, Capability.SKILLS, Capability.AGENTS,
                        Capability.POLICIES, Capability.HOOKS, Capability.PLUGINS,
                        Capability.EFFECTIVE_CONTEXT),
                List.of(
                        DiscoveryRule.fileName("AGENTS.md", INSTRUCTION),
                        DiscoveryRule.fileName("AGENTS.override.md", INSTRUCTION),
                        DiscoveryRule.treeFileName(".agents/skills", "SKILL.md", SKILL),
                        DiscoveryRule.exactFile(".codex/config.toml", RUNTIME_CONFIG),
                        DiscoveryRule.treeSuffix(".codex/agents", ".toml", AGENT),
                        DiscoveryRule.treeSuffix(".codex/rules", ".rules", POLICY),
                        DiscoveryRule.exactFile(".codex/hooks.json", HOOK),
                        DiscoveryRule.exactFile(".codex-plugin/plugin.json", PLUGIN)),
                List.of(evidence("https://developers.openai.com/codex/guides/agents-md",
                        "Codex project instructions and scope"),
                        evidence("https://developers.openai.com/codex/skills",
                                "Codex project skills")));
    }

    private static HostDescriptor claude() {
        return new HostDescriptor(
                "claude-code",
                "Claude Code",
                RoadmapTier.CORE,
                AdapterMaturity.INVENTORY,
                "unverified-phase1",
                EnumSet.of(Capability.INSTRUCTIONS, Capability.SCOPED_RULES, Capability.SKILLS,
                        Capability.AGENTS, Capability.POLICIES, Capability.HOOKS,
                        Capability.PLUGINS, Capability.WORKFLOWS, Capability.EFFECTIVE_CONTEXT),
                List.of(
                        DiscoveryRule.fileName("CLAUDE.md", INSTRUCTION),
                        DiscoveryRule.fileName("CLAUDE.local.md", INSTRUCTION),
                        DiscoveryRule.treeSuffix(".claude/rules", ".md", INSTRUCTION),
                        DiscoveryRule.treeFileName(".claude/skills", "SKILL.md", SKILL),
                        DiscoveryRule.treeSuffix(".claude/commands", ".md", WORKFLOW),
                        DiscoveryRule.treeSuffix(".claude/agents", ".md", AGENT),
                        DiscoveryRule.exactFile(".claude/settings.json", RUNTIME_CONFIG),
                        DiscoveryRule.exactFile(".claude/settings.local.json", RUNTIME_CONFIG),
                        DiscoveryRule.exactFile(".claude-plugin/plugin.json", PLUGIN)),
                List.of(evidence("https://code.claude.com/docs/en/memory",
                        "Claude Code memory and rules"),
                        evidence("https://code.claude.com/docs/en/skills",
                                "Claude Code skills")));
    }

    private static HostDescriptor cursor() {
        return new HostDescriptor(
                "cursor",
                "Cursor",
                RoadmapTier.BETA_ADAPTER,
                AdapterMaturity.INVENTORY,
                "unverified-phase1",
                EnumSet.of(Capability.INSTRUCTIONS, Capability.SCOPED_RULES,
                        Capability.WORKFLOWS, Capability.SURFACE_AWARE),
                List.of(
                        DiscoveryRule.fileName("AGENTS.md", INSTRUCTION),
                        DiscoveryRule.treeSuffix(".cursor/rules", ".mdc", INSTRUCTION),
                        DiscoveryRule.treeSuffix(".cursor/commands", ".md", WORKFLOW),
                        DiscoveryRule.exactFile(".cursorrules", INSTRUCTION)),
                List.of(evidence("https://docs.cursor.com/context/rules",
                        "Cursor project rules and activation modes"),
                        evidence("https://docs.cursor.com/en/agent/chat/commands",
                                "Cursor reusable commands")));
    }

    private static HostDescriptor copilot() {
        return new HostDescriptor(
                "github-copilot",
                "GitHub Copilot",
                RoadmapTier.BETA_ADAPTER,
                AdapterMaturity.INVENTORY,
                "unverified-phase1-surface-dependent",
                EnumSet.of(Capability.INSTRUCTIONS, Capability.SCOPED_RULES, Capability.SKILLS,
                        Capability.AGENTS, Capability.SURFACE_AWARE),
                List.of(
                        DiscoveryRule.exactFile(".github/copilot-instructions.md", INSTRUCTION),
                        DiscoveryRule.treeSuffix(".github/instructions", ".instructions.md", INSTRUCTION),
                        DiscoveryRule.treeSuffix(".github/agents", ".md", AGENT),
                        DiscoveryRule.treeFileName(".github/skills", "SKILL.md", SKILL)),
                List.of(evidence(
                        "https://docs.github.com/en/copilot/reference/custom-instructions-support",
                        "Copilot instruction files and surface support"),
                        evidence(
                                "https://docs.github.com/en/copilot/reference/custom-agents-configuration",
                                "Copilot custom agent profiles")));
    }

    private static HostDescriptor windsurfDevin() {
        return new HostDescriptor(
                "windsurf-devin",
                "Windsurf / Devin Desktop",
                RoadmapTier.BETA_ADAPTER,
                AdapterMaturity.INVENTORY,
                "unverified-phase1-migration-state",
                EnumSet.of(Capability.INSTRUCTIONS, Capability.SCOPED_RULES, Capability.SKILLS,
                        Capability.WORKFLOWS, Capability.SURFACE_AWARE),
                List.of(
                        DiscoveryRule.fileName("AGENTS.md", INSTRUCTION),
                        DiscoveryRule.treeSuffix(".devin/rules", ".md", INSTRUCTION),
                        DiscoveryRule.treeSuffix(".windsurf/rules", ".md", INSTRUCTION),
                        DiscoveryRule.treeSuffix(".windsurf/workflows", ".md", WORKFLOW),
                        DiscoveryRule.treeFileName(".agents/skills", "SKILL.md", SKILL),
                        DiscoveryRule.treeFileName(".devin/skills", "SKILL.md", SKILL),
                        DiscoveryRule.treeFileName(".windsurf/skills", "SKILL.md", SKILL)),
                List.of(evidence("https://docs.devin.ai/desktop/cascade/memories",
                        "Current and legacy rule locations"),
                        evidence("https://docs.devin.ai/product-guides/skills",
                                "Devin Agent Skills locations and fields")));
    }

    private static OfficialEvidence evidence(String url, String note) {
        return new OfficialEvidence(URI.create(url), EVIDENCE_DATE, note);
    }
}
