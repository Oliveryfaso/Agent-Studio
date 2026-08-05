package dev.agentconfig.workbench.transaction;

import java.util.Objects;
import java.util.Optional;

/** Explicit content-bearing view used only when a caller asks to inspect the real fixture diff. */
public record PreparedFixtureSkillChange(
        FixtureSkillChangePlan plan,
        Optional<String> exactReplacementDiff) {
    public PreparedFixtureSkillChange {
        Objects.requireNonNull(plan, "plan");
        exactReplacementDiff = Objects.requireNonNull(exactReplacementDiff, "exactReplacementDiff");
        boolean ready = plan.status() == FixtureSkillChangePlan.Status.READY_CREATE
                || plan.status() == FixtureSkillChangePlan.Status.READY_REPLACE;
        if (ready != exactReplacementDiff.isPresent()) {
            throw new IllegalArgumentException("diff content disagrees with plan status");
        }
    }
}
