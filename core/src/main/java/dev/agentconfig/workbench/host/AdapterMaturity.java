package dev.agentconfig.workbench.host;

/** Ordered runtime gates. A roadmap label never grants these operations. */
public enum AdapterMaturity {
    INVENTORY,
    READ,
    CONVERSION_PREVIEW,
    APPLY;

    public boolean includes(AdapterMaturity required) {
        return ordinal() >= required.ordinal();
    }
}
