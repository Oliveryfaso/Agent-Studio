package dev.agentconfig.workbench.context;

/** Versioned, deliberately narrow claims for project-level host semantics. */
public enum ProjectSemanticProfile {
    CODEX_PROJECT_V1("codex", "codex-project-semantics-v1"),
    CLAUDE_CODE_PROJECT_V1("claude-code", "claude-code-project-semantics-v1");

    private final String hostId;
    private final String id;

    ProjectSemanticProfile(String hostId, String id) {
        this.hostId = hostId;
        this.id = id;
    }

    public String hostId() {
        return hostId;
    }

    public String id() {
        return id;
    }

    public static ProjectSemanticProfile forHost(String hostId) {
        for (ProjectSemanticProfile profile : values()) {
            if (profile.hostId.equals(hostId)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("No project semantic profile for host: " + hostId);
    }

    public static ProjectSemanticProfile fromId(String id) {
        for (ProjectSemanticProfile profile : values()) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown project semantic profile: " + id);
    }
}
