package dev.agentconfig.workbench.host;

/** Product priority only; this enum never authorizes filesystem operations. */
public enum RoadmapTier {
    CORE,
    BETA_ADAPTER,
    PREVIEW_ADAPTER,
    EXPORT_ONLY
}
