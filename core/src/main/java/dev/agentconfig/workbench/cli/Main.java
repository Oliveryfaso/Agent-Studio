package dev.agentconfig.workbench.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = Cli.phaseOneDefaults().run(
                args,
                new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                new PrintWriter(System.err, true, StandardCharsets.UTF_8));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
