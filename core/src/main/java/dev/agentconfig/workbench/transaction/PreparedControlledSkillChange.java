package dev.agentconfig.workbench.transaction;

import java.util.Optional;

/** A metadata plan plus an explicit local-only exact replacement diff. */
public record PreparedControlledSkillChange(
        ControlledSkillChangePlan plan,
        Optional<String> exactReplacementDiff) {
    public PreparedControlledSkillChange {
        if (plan == null) throw new NullPointerException("plan");
        exactReplacementDiff = exactReplacementDiff == null
                ? Optional.empty() : exactReplacementDiff;
        if (exactReplacementDiff.isPresent() != plan.applyEligible()) {
            throw new IllegalArgumentException("diff eligibility");
        }
    }
}
