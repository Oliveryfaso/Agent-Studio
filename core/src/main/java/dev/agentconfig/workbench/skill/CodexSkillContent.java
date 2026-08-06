package dev.agentconfig.workbench.skill;

import dev.agentconfig.workbench.skilldraft.CodexSkillFormProjection;

/** One explicitly selected project Skill body and its loss-aware form projection. */
public record CodexSkillContent(
        String logicalPath,
        String content,
        int byteSize,
        String sha256,
        CodexSkillFormProjection projection) {}
