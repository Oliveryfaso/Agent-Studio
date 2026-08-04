package dev.agentconfig.workbench.conversion;

import java.util.Objects;

public record CapabilityDelta(
        CapabilityChange tools,
        CapabilityChange permissions,
        CapabilityChange network,
        CapabilityChange modelInvocation,
        CapabilityChange automaticInvocation,
        CapabilityChange executableBehavior) {
    public CapabilityDelta {
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(modelInvocation, "modelInvocation");
        Objects.requireNonNull(automaticInvocation, "automaticInvocation");
        Objects.requireNonNull(executableBehavior, "executableBehavior");
    }

    public static CapabilityDelta instructionOnlyUnknown() {
        return new CapabilityDelta(
                CapabilityChange.UNCHANGED,
                CapabilityChange.UNCHANGED,
                CapabilityChange.UNCHANGED,
                CapabilityChange.UNCHANGED,
                CapabilityChange.UNKNOWN,
                CapabilityChange.UNCHANGED);
    }

    public static CapabilityDelta unchanged() {
        return new CapabilityDelta(
                CapabilityChange.UNCHANGED,
                CapabilityChange.UNCHANGED,
                CapabilityChange.UNCHANGED,
                CapabilityChange.UNCHANGED,
                CapabilityChange.UNCHANGED,
                CapabilityChange.UNCHANGED);
    }

    public boolean blocksReadyStatus() {
        return tools.blocksReadyStatus()
                || permissions.blocksReadyStatus()
                || network.blocksReadyStatus()
                || modelInvocation.blocksReadyStatus()
                || automaticInvocation.blocksReadyStatus()
                || executableBehavior.blocksReadyStatus();
    }
}
