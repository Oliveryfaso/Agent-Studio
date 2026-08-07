package dev.agentconfig.workbench.localweb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Development entry point for the loopback API. */
public final class LocalWorkbenchMain {
    private LocalWorkbenchMain() {}

    public static void main(String[] args) throws Exception {
        if ((args.length != 2 && args.length != 4) || !"--state-root".equals(args[0])
                || (args.length == 4 && !"--ui-root".equals(args[2]))) {
            System.err.println("Usage: LocalWorkbenchMain --state-root <existing-directory>"
                    + " [--ui-root <built-ui-directory>]");
            System.exit(2);
        }
        run(Path.of(args[1]), args.length == 4 ? Path.of(args[3]) : null);
    }

    private static void run(Path stateRoot, Path uiRoot) throws IOException, InterruptedException {
        LocalWorkbenchServer server = uiRoot == null
                ? LocalWorkbenchServer.start(stateRoot)
                : LocalWorkbenchServer.startDesktop(stateRoot, uiRoot);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "acw-loopback-shutdown"));
        System.out.println("Agent Config Workbench loopback API");
        System.out.println("Base URL: " + server.baseUri());
        System.out.println("Open: " + server.launchUri());
        System.out.println("The fragment token is process-local and is not written to disk.");
        new CountDownLatch(1).await();
    }
}
