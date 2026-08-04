package dev.agentconfig.workbench.host;

/**
 * Phase 1 adapters are inert manifest providers. Filesystem I/O belongs to the
 * scanner, and parsing/rendering/writing are deliberately absent from this API.
 */
public interface HostAdapter {
    int API_VERSION = 1;

    int apiVersion();

    String adapterVersion();

    HostDescriptor descriptor();
}
