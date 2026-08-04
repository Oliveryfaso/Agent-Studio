package dev.agentconfig.workbench.ir;

public record SourceIdentity(String hostId, String sourceId) {
    public SourceIdentity {
        hostId = IrValidation.id(hostId, "hostId");
        sourceId = IrValidation.id(sourceId, "sourceId");
    }
}
