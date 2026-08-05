package dev.agentconfig.workbench.localweb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Development entry point for the loopback API. */
public final class LocalWorkbenchMain {
    private LocalWorkbenchMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--state-root".equals(args[0])) {
            System.err.println("Usage: LocalWorkbenchMain --state-root <existing-directory>");
            System.exit(2);
        }
        run(Path.of(args[1]));
    }

    private static void run(Path stateRoot) throws IOException, InterruptedException {
        LocalWorkbenchServer server = LocalWorkbenchServer.start(stateRoot);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "acw-loopback-shutdown"));
        System.out.println("Agent Config Workbench loopback API");
        System.out.println("Base URL: " + server.baseUri());
        System.out.println("Session token: " + server.sessionToken());
        System.out.println("The token is process-local and is not written to disk.");
        new CountDownLatch(1).await();
    }
}
