package dev.agentconfig.workbench.host;

import java.util.Objects;

public record ManifestHostAdapter(int apiVersion, String adapterVersion, HostDescriptor descriptor)
        implements HostAdapter {

    public ManifestHostAdapter {
        if (apiVersion != HostAdapter.API_VERSION) {
            throw new IllegalArgumentException("Unsupported HostAdapter API version: " + apiVersion);
        }
        adapterVersion = Objects.requireNonNull(adapterVersion, "adapterVersion").strip();
        Objects.requireNonNull(descriptor, "descriptor");
        if (adapterVersion.isEmpty()) {
            throw new IllegalArgumentException("Adapter version must not be empty");
        }
    }

    public static ManifestHostAdapter phaseOne(HostDescriptor descriptor) {
        return new ManifestHostAdapter(HostAdapter.API_VERSION, "0.1.0-phase1", descriptor);
    }
}
