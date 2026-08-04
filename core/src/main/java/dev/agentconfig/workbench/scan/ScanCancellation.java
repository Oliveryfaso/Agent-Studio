package dev.agentconfig.workbench.scan;

@FunctionalInterface
public interface ScanCancellation {
    boolean isCancellationRequested();

    static ScanCancellation neverCancelled() {
        return () -> false;
    }
}
