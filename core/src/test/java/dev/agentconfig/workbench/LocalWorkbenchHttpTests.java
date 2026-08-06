package dev.agentconfig.workbench;

import dev.agentconfig.workbench.localweb.LocalWorkbenchServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalWorkbenchHttpTests {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private int passed;

    public static void main(String[] args) throws Exception {
        new LocalWorkbenchHttpTests().runAll();
    }

    private void runAll() throws Exception {
        run("runtime binds loopback without CORS", this::runtime);
        run("built UI is same-origin with fragment token", this::staticUi);
        run("local API requires exact origin and bearer token", this::authBoundary);
        run("inventory lists previewable Skills without content", this::inventory);
        run("inventory distinguishes empty and partial results", this::inventoryStates);
        run("inventory rejects invalid requests with typed errors", this::inventoryErrors);
        run("preview apply rollback is byte identical", this::endToEnd);
        run("empty project can create and undo its first Skill", this::createFirstSkill);
        run("create approval rejects target and parent topology changes", this::createGuards);
        run("v1 update transaction remains rollback compatible", this::v1RollbackCompatibility);
        run("create rollback preserves replaced parent directory", this::replacedParentGuard);
        run("stale approval preserves external edit", this::staleApproval);
        run("missing existing target is typed blocked", this::missingTarget);
        run("blocked workspace link never exposes authorized scope", this::blockedWorkspaceLink);
        run("JSON and media type boundaries are strict", this::strictJsonBoundary);
        run("oversized body is rejected", this::bodyBudget);
        System.out.printf("Local workbench HTTP tests: %d passed%n", passed);
    }

    private void runtime() throws Exception {
        withFixture(fixture -> {
            URI base = fixture.server().baseUri();
            equal("127.0.0.1", base.getHost(), "host");
            check(base.getPort() > 0, "random port");
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    base.resolve("api/v1/runtime")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(200, response.statusCode(), "status");
            contains(response.body(), "\"status\": \"READY\"");
            contains(response.body(), "\"skillInventory\": true");
            contains(response.body(), "\"skillCreate\": true");
            check(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
                    "CORS header present");
        });
    }

    private void authBoundary() throws Exception {
        withFixture(fixture -> {
            String body = previewBody(fixture.workspace(), true);
            HttpResponse<String> noToken = fixture.post("preview", body, null,
                    fixture.origin());
            equal(401, noToken.statusCode(), "missing token");
            contains(noToken.body(), "SESSION_TOKEN_INVALID");

            HttpResponse<String> wrongOrigin = fixture.post("preview", body,
                    fixture.server().sessionToken(), "http://127.0.0.1:9");
            equal(403, wrongOrigin.statusCode(), "wrong origin");
            contains(wrongOrigin.body(), "ORIGIN_FORBIDDEN");

            HttpRequest options = HttpRequest.newBuilder(fixture.endpoint("preview"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> method = client.send(options,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            equal(405, method.statusCode(), "options");

            HttpResponse<String> inventory = fixture.postInventory(
                    inventoryBody(fixture.workspace()), null, fixture.origin());
            equal(401, inventory.statusCode(), "inventory missing token");
        });
    }

    private void inventory() throws Exception {
        withFixture(fixture -> {
            String marker = "PRIVATE_SKILL_BODY_MARKER";
            Files.writeString(fixture.target(), "---\nname: review-api-change\n"
                    + "description: Review API changes.\n---\n\n" + marker + "\n",
                    StandardCharsets.UTF_8);
            Path alpha = Files.createDirectories(
                    fixture.workspace().resolve(".agents/skills/alpha-check")).resolve("SKILL.md");
            Files.writeString(alpha, "---\nname: alpha-check\n"
                    + "description: Check alpha changes.\n---\n", StandardCharsets.UTF_8);
            HttpResponse<String> response = fixture.postInventory(
                    inventoryBody(fixture.workspace()));
            equal(200, response.statusCode(), "status");
            contains(response.body(), "\"command\": \"skill-inventory\"");
            contains(response.body(), "\"status\": \"COMPLETE\"");
            contains(response.body(), "\"name\": \"review-api-change\"");
            contains(response.body(), "\"logicalPath\": "
                    + "\".agents/skills/review-api-change/SKILL.md\"");
            contains(response.body(), "\"state\": \"MINIMAL_METADATA_VALID\"");
            contains(response.body(), "\"availableForPreview\": true");
            contains(response.body(), "\"contentIncluded\": false");
            contains(response.body(), "\"writesPerformed\": false");
            check(response.body().indexOf("\"name\": \"alpha-check\"")
                    < response.body().indexOf("\"name\": \"review-api-change\""),
                    "Skill ordering");
            check(!response.body().contains(marker), "inventory exposed Skill content");
            check(!response.body().contains("sha256"), "inventory exposed hash");
        });
    }

    private void inventoryStates() throws Exception {
        withFixture(fixture -> {
            Files.delete(fixture.target());
            Files.delete(fixture.target().getParent());
            Files.delete(fixture.workspace().resolve(".agents/skills"));
            Files.delete(fixture.workspace().resolve(".agents"));
            HttpResponse<String> empty = fixture.postInventory(
                    inventoryBody(fixture.workspace()));
            equal(200, empty.statusCode(), "empty status");
            contains(empty.body(), "\"status\": \"COMPLETE\"");
            contains(empty.body(), "\"skills\": [\n  ]");

            Path skillDirectory = Files.createDirectories(
                    fixture.workspace().resolve(".agents/skills/partial-skill"));
            Files.writeString(skillDirectory.resolve("SKILL.md"),
                    "---\nname: partial-skill\ndescription: Partial fixture.\n---\n",
                    StandardCharsets.UTF_8);
            Path outside = Files.writeString(
                    fixture.workspace().getParent().resolve("outside.txt"), "outside\n");
            try {
                Files.createSymbolicLink(skillDirectory.resolve("support.txt"), outside);
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                return;
            }
            HttpResponse<String> partial = fixture.postInventory(
                    inventoryBody(fixture.workspace()));
            equal(200, partial.statusCode(), "partial status");
            contains(partial.body(), "\"status\": \"PARTIAL\"");
            contains(partial.body(), "\"state\": \"PARTIAL\"");
            contains(partial.body(), "\"availableForPreview\": false");
            contains(partial.body(), "\"blocking\": 1");
            check(!partial.body().contains(outside.toString()), "outside path exposed");
        });
    }

    private void inventoryErrors() throws Exception {
        withFixture(fixture -> {
            HttpResponse<String> invalidPackage = fixture.postInventory(
                    inventoryBody(fixture.workspace()));
            contains(invalidPackage.body(), "\"state\": \"INVALID\"");
            contains(invalidPackage.body(), "\"availableForPreview\": true");

            String wrongHost = "{\"hostId\":\"claude-code\",\"workspacePath\":"
                    + json(fixture.workspace().toString()) + "}";
            HttpResponse<String> wrongHostResponse = fixture.postInventory(wrongHost);
            equal(400, wrongHostResponse.statusCode(), "wrong host");
            contains(wrongHostResponse.body(), "INPUT_INVALID");

            String unknown = inventoryBody(fixture.workspace());
            unknown = unknown.substring(0, unknown.length() - 1) + ",\"typo\":true}";
            equal(400, fixture.postInventory(unknown).statusCode(), "unknown field");
            equal(400, fixture.postInventory("{\"hostId\":\"codex\"}")
                    .statusCode(), "missing workspace");
            Path missing = fixture.workspace().resolve("missing");
            equal(400, fixture.postInventory(inventoryBody(missing))
                    .statusCode(), "missing path");
        });
    }

    private void staticUi() throws Exception {
        Path base = Files.createTempDirectory("acw-http-test-");
        try {
            Path state = Files.createDirectory(base.resolve("state"));
            Path ui = Files.createDirectory(base.resolve("ui"));
            Files.writeString(ui.resolve("index.html"), "<!doctype html><title>Workbench</title>",
                    StandardCharsets.UTF_8);
            Path assets = Files.createDirectory(ui.resolve("assets"));
            Files.writeString(assets.resolve("app.js"), "export {};", StandardCharsets.UTF_8);
            try (LocalWorkbenchServer server = LocalWorkbenchServer.start(state, ui)) {
                check(server.launchUri().toString().startsWith(
                        server.baseUri().toString() + "#token="), "launch token");
                HttpResponse<String> index = client.send(HttpRequest.newBuilder(
                        server.baseUri()).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                equal(200, index.statusCode(), "index");
                contains(index.body(), "Workbench");
                contains(index.headers().firstValue("Content-Security-Policy").orElse(""),
                        "connect-src 'self'");
                equal("no-store", index.headers().firstValue("Cache-Control").orElse(""),
                        "index cache");

                HttpResponse<String> asset = client.send(HttpRequest.newBuilder(
                        server.baseUri().resolve("assets/app.js")).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                equal(200, asset.statusCode(), "asset");
                contains(asset.headers().firstValue("Cache-Control").orElse(""), "immutable");
                check(!index.body().contains(server.sessionToken()), "token in HTML");
            }
        } finally {
            deleteOwned(base);
        }
    }

    private void endToEnd() throws Exception {
        withFixture(fixture -> {
            byte[] original = Files.readAllBytes(fixture.target());
            HttpResponse<String> preview = fixture.post("preview",
                    previewBody(fixture.workspace(), true));
            equal(200, preview.statusCode(), "preview");
            contains(preview.body(), "\"status\": \"READY_REPLACE\"");
            contains(preview.body(), "\"diffIncluded\": true");
            contains(preview.body(), "-old skill body\\n");
            contains(preview.body(), json(fixture.target().toRealPath().toString()));
            String token = capture(preview.body(),
                    "\\\"approvalToken\\\": \\\"(acw_apply1_[0-9a-f]{64})\\\"");

            HttpResponse<String> apply = fixture.post("apply", applyBody(
                    fixture.workspace(), token));
            equal(200, apply.statusCode(), "apply");
            contains(apply.body(), "\"status\": \"VERIFIED_APPLIED\"");
            contains(apply.body(), "\"targetWritesPerformed\": true");
            String transaction = capture(apply.body(),
                    "\\\"transactionId\\\": \\\"([0-9a-f-]{36})\\\"");
            check(!java.util.Arrays.equals(original, Files.readAllBytes(fixture.target())),
                    "target unchanged");

            HttpResponse<String> rollback = fixture.post("rollback",
                    rollbackBody(fixture.workspace(), transaction));
            equal(200, rollback.statusCode(), "rollback");
            contains(rollback.body(), "\"status\": \"ROLLED_BACK\"");
            check(java.util.Arrays.equals(original, Files.readAllBytes(fixture.target())),
                    "rollback bytes");
        });
    }

    private void staleApproval() throws Exception {
        withFixture(fixture -> {
            HttpResponse<String> preview = fixture.post("preview",
                    previewBody(fixture.workspace(), false));
            String token = capture(preview.body(),
                    "\\\"approvalToken\\\": \\\"(acw_apply1_[0-9a-f]{64})\\\"");
            Files.writeString(fixture.target(), "external edit\n", StandardCharsets.UTF_8);
            HttpResponse<String> apply = fixture.post("apply",
                    applyBody(fixture.workspace(), token));
            equal(409, apply.statusCode(), "status");
            contains(apply.body(), "\"status\": \"APPROVAL_MISMATCH\"");
            equal("external edit\n", Files.readString(fixture.target()), "external edit");
        });
    }

    private void createFirstSkill() throws Exception {
        withFixture(fixture -> {
            Files.delete(fixture.target());
            Files.delete(fixture.target().getParent());
            Files.delete(fixture.workspace().resolve(".agents/skills"));
            Files.delete(fixture.workspace().resolve(".agents"));

            HttpResponse<String> preview = fixture.post("preview",
                    previewBody(fixture.workspace(), true, "CREATE"));
            equal(200, preview.statusCode(), "preview status");
            contains(preview.body(), "\"operation\": \"CREATE\"");
            contains(preview.body(), "\"status\": \"READY_CREATE\"");
            contains(preview.body(), "--- /dev/null\\n");
            check(!Files.exists(fixture.target(), LinkOption.NOFOLLOW_LINKS),
                    "preview created target");
            String token = capture(preview.body(),
                    "\\\"approvalToken\\\": \\\"(acw_apply1_[0-9a-f]{64})\\\"");

            HttpResponse<String> apply = fixture.post("apply", applyBody(
                    fixture.workspace(), token, "CREATE"));
            equal(200, apply.statusCode(), "apply status");
            contains(apply.body(), "\"operation\": \"CREATE\"");
            contains(apply.body(), "\"status\": \"VERIFIED_APPLIED\"");
            check(Files.isRegularFile(fixture.target(), LinkOption.NOFOLLOW_LINKS),
                    "target not created");
            String transaction = capture(apply.body(),
                    "\\\"transactionId\\\": \\\"([0-9a-f-]{36})\\\"");

            HttpResponse<String> rollback = fixture.post("rollback",
                    rollbackBody(fixture.workspace(), transaction));
            equal(200, rollback.statusCode(), "rollback status");
            contains(rollback.body(), "\"status\": \"ROLLED_BACK\"");
            contains(rollback.body(), "CONTROLLED_CREATE_ROLLBACK_VERIFIED");
            check(!Files.exists(fixture.target(), LinkOption.NOFOLLOW_LINKS),
                    "create rollback kept target");
            check(!Files.exists(fixture.workspace().resolve(".agents"),
                    LinkOption.NOFOLLOW_LINKS), "create rollback kept owned directories");
        });
    }

    private void createGuards() throws Exception {
        withFixture(fixture -> {
            HttpResponse<String> existing = fixture.post("preview",
                    previewBody(fixture.workspace(), true, "CREATE"));
            equal(422, existing.statusCode(), "existing create status");
            contains(existing.body(), "CREATE_TARGET_ALREADY_EXISTS");

            Files.delete(fixture.target());
            String targetToken = capture(fixture.post("preview",
                    previewBody(fixture.workspace(), true, "CREATE")).body(),
                    "\\\"approvalToken\\\": \\\"(acw_apply1_[0-9a-f]{64})\\\"");
            Files.writeString(fixture.target(), "external file\n", StandardCharsets.UTF_8);
            HttpResponse<String> targetChanged = fixture.post("apply", applyBody(
                    fixture.workspace(), targetToken, "CREATE"));
            equal(409, targetChanged.statusCode(), "created target status");
            contains(targetChanged.body(), "APPROVAL_MISMATCH");
            equal("external file\n", Files.readString(fixture.target()), "external target");
        });
        withFixture(fixture -> {
            Files.delete(fixture.target());
            Files.delete(fixture.target().getParent());
            Files.delete(fixture.workspace().resolve(".agents/skills"));
            Files.delete(fixture.workspace().resolve(".agents"));
            String token = capture(fixture.post("preview",
                    previewBody(fixture.workspace(), true, "CREATE")).body(),
                    "\\\"approvalToken\\\": \\\"(acw_apply1_[0-9a-f]{64})\\\"");
            Files.createDirectory(fixture.workspace().resolve(".agents"));
            HttpResponse<String> changedParents = fixture.post("apply", applyBody(
                    fixture.workspace(), token, "CREATE"));
            equal(409, changedParents.statusCode(), "parent topology status");
            contains(changedParents.body(), "APPROVAL_MISMATCH");
            check(!Files.exists(fixture.target(), LinkOption.NOFOLLOW_LINKS),
                    "topology change created target");
        });
    }

    private void v1RollbackCompatibility() throws Exception {
        withFixture(fixture -> {
            byte[] original = Files.readAllBytes(fixture.target());
            String token = capture(fixture.post("preview",
                    previewBody(fixture.workspace(), true)).body(),
                    "\\\"approvalToken\\\": \\\"(acw_apply1_[0-9a-f]{64})\\\"");
            HttpResponse<String> apply = fixture.post("apply",
                    applyBody(fixture.workspace(), token));
            String transaction = capture(apply.body(),
                    "\\\"transactionId\\\": \\\"([0-9a-f-]{36})\\\"");
            downgradeManifestToV1(fixture.state().resolve(transaction).resolve("manifest.bin"));
            HttpResponse<String> rollback = fixture.post("rollback",
                    rollbackBody(fixture.workspace(), transaction));
            equal(200, rollback.statusCode(), "v1 rollback status");
            contains(rollback.body(), "\"status\": \"ROLLED_BACK\"");
            check(java.util.Arrays.equals(original, Files.readAllBytes(fixture.target())),
                    "v1 rollback bytes");
        });
    }

    private void replacedParentGuard() throws Exception {
        withFixture(fixture -> {
            Files.delete(fixture.target());
            Files.delete(fixture.target().getParent());
            Files.delete(fixture.workspace().resolve(".agents/skills"));
            Files.delete(fixture.workspace().resolve(".agents"));
            String token = capture(fixture.post("preview",
                    previewBody(fixture.workspace(), true, "CREATE")).body(),
                    "\\\"approvalToken\\\": \\\"(acw_apply1_[0-9a-f]{64})\\\"");
            HttpResponse<String> apply = fixture.post("apply", applyBody(
                    fixture.workspace(), token, "CREATE"));
            String transaction = capture(apply.body(),
                    "\\\"transactionId\\\": \\\"([0-9a-f-]{36})\\\"");

            Path replacementParent = fixture.target().getParent();
            Path parked = fixture.workspace().resolve("parked-skill.md");
            Files.move(fixture.target(), parked);
            Files.delete(replacementParent);
            Files.createDirectory(replacementParent);
            Files.move(parked, fixture.target());

            HttpResponse<String> rollback = fixture.post("rollback",
                    rollbackBody(fixture.workspace(), transaction));
            equal(200, rollback.statusCode(), "rollback status");
            contains(rollback.body(), "\"status\": \"ROLLED_BACK\"");
            check(!Files.exists(fixture.target(), LinkOption.NOFOLLOW_LINKS),
                    "rollback kept candidate");
            check(Files.isDirectory(replacementParent, LinkOption.NOFOLLOW_LINKS),
                    "rollback deleted replaced parent");
        });
    }

    private static void downgradeManifestToV1(Path path) throws Exception {
        String transactionId;
        String rootIdentity;
        String logicalPath;
        String preimageSha256;
        String preimageIdentity;
        String permissions;
        String candidateSha256;
        String resultIdentity;
        String state;
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(Files.readAllBytes(path)))) {
            equal("ACW-CONTROLLED-SKILL", input.readUTF(), "manifest magic");
            equal(2, input.readInt(), "manifest version");
            transactionId = input.readUTF();
            rootIdentity = input.readUTF();
            logicalPath = input.readUTF();
            check(input.readBoolean(), "update transaction expected");
            preimageSha256 = input.readUTF();
            preimageIdentity = input.readUTF();
            permissions = input.readUTF();
            candidateSha256 = input.readUTF();
            resultIdentity = input.readUTF();
            equal(0, input.readInt(), "update created parent count");
            state = input.readUTF();
            input.readUTF();
            equal(-1, input.read(), "manifest trailing bytes");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("ACW-CONTROLLED-SKILL");
            output.writeInt(1);
            output.writeUTF(transactionId);
            output.writeUTF(rootIdentity);
            output.writeUTF(logicalPath);
            output.writeUTF(preimageSha256);
            output.writeUTF(preimageIdentity);
            output.writeUTF(permissions);
            output.writeUTF(candidateSha256);
            output.writeUTF(resultIdentity);
            output.writeUTF(state);
            output.writeUTF(hash(tuple("controlled-manifest:v1", transactionId, rootIdentity,
                    logicalPath, preimageSha256, preimageIdentity, permissions,
                    candidateSha256, resultIdentity, state)));
        }
        Files.write(path, bytes.toByteArray());
    }

    private static String tuple(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value.length()).append(':').append(value).append(';');
        return result.toString();
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void missingTarget() throws Exception {
        withFixture(fixture -> {
            Files.delete(fixture.target());
            HttpResponse<String> preview = fixture.post("preview",
                    previewBody(fixture.workspace(), true));
            equal(422, preview.statusCode(), "status");
            contains(preview.body(), "EXISTING_TARGET_REQUIRED");
            try (var entries = Files.list(fixture.state())) {
                check(entries.findAny().isEmpty(), "preview wrote state");
            }
        });
    }

    private void bodyBudget() throws Exception {
        withFixture(fixture -> {
            String body = "{\"hostId\":\"codex\",\"workspacePath\":\""
                    + "x".repeat(50 * 1024) + "\"}";
            HttpResponse<String> response = fixture.post("preview", body);
            equal(413, response.statusCode(), "status");
            contains(response.body(), "BODY_TOO_LARGE");
        });
    }

    private void blockedWorkspaceLink() throws Exception {
        withFixture(fixture -> {
            Path linked = fixture.workspace().getParent().resolve("linked-workspace");
            try {
                Files.createSymbolicLink(linked, fixture.workspace());
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                return;
            }
            HttpResponse<String> response = fixture.post("preview", previewBody(linked, true));
            equal(422, response.statusCode(), "status");
            contains(response.body(), "WORKSPACE_ROOT_IS_LINK");
            contains(response.body(), "\"authorizedRoot\": null");
            contains(response.body(), "\"targetPath\": null");
        });
    }

    private void strictJsonBoundary() throws Exception {
        withFixture(fixture -> {
            String valid = previewBody(fixture.workspace(), false);
            HttpResponse<String> jsonp = fixture.postWithType("preview", valid,
                    "application/jsonp");
            equal(415, jsonp.statusCode(), "jsonp");

            String loneSurrogate = valid.replace(json(request()), "\"\\uD800\"");
            HttpResponse<String> surrogate = fixture.post("preview", loneSurrogate);
            equal(400, surrogate.statusCode(), "surrogate");
            contains(surrogate.body(), "JSON_INVALID");

            String duplicate = valid.substring(0, valid.length() - 1)
                    + ",\"hostId\":\"codex\"}";
            equal(400, fixture.post("preview", duplicate).statusCode(), "duplicate");

            String unknown = valid.substring(0, valid.length() - 1) + ",\"typo\":true}";
            HttpResponse<String> unknownResponse = fixture.post("preview", unknown);
            equal(400, unknownResponse.statusCode(), "unknown field");
            contains(unknownResponse.body(), "INPUT_INVALID");
        });
    }

    private static String previewBody(Path workspace, boolean diff) {
        return "{\"hostId\":\"codex\",\"workspacePath\":" + json(workspace.toString())
                + ",\"guidedRequest\":" + json(request()) + ",\"includeDiff\":" + diff + "}";
    }

    private static String previewBody(Path workspace, boolean diff, String operation) {
        return "{\"hostId\":\"codex\",\"workspacePath\":" + json(workspace.toString())
                + ",\"guidedRequest\":" + json(request()) + ",\"includeDiff\":" + diff
                + ",\"operation\":" + json(operation) + "}";
    }

    private static String inventoryBody(Path workspace) {
        return "{\"hostId\":\"codex\",\"workspacePath\":"
                + json(workspace.toString()) + "}";
    }

    private static String applyBody(Path workspace, String token) {
        return "{\"hostId\":\"codex\",\"workspacePath\":" + json(workspace.toString())
                + ",\"guidedRequest\":" + json(request()) + ",\"approvalToken\":"
                + json(token) + "}";
    }

    private static String applyBody(Path workspace, String token, String operation) {
        return "{\"hostId\":\"codex\",\"workspacePath\":" + json(workspace.toString())
                + ",\"guidedRequest\":" + json(request()) + ",\"approvalToken\":"
                + json(token) + ",\"operation\":" + json(operation) + "}";
    }

    private static String rollbackBody(Path workspace, String transaction) {
        return "{\"hostId\":\"codex\",\"workspacePath\":" + json(workspace.toString())
                + ",\"transactionId\":" + json(transaction) + "}";
    }

    private static String request() {
        return "repeated-workflow: true\nclear-trigger: true\nsuccess-criteria: true\n"
                + "confirmed-artifact: skill\nconfirmed-scope: project\n"
                + "name: review-api-change\ndescription: Review API changes.\n"
                + "goal: Produce a bounded review.\ninput: Changed files\noutput: Findings\n"
                + "trigger: Use for API changes.\nexclusion: Do not use for UI changes.\n"
                + "boundary-example: A CSS edit is outside scope.\n"
                + "should-trigger: Review endpoint\nshould-trigger: Review schema\n"
                + "should-trigger: Review migration\nshould-not-trigger: Review CSS\n"
                + "should-not-trigger: Draft marketing\nshould-not-trigger: Rename image\n"
                + "step: Inspect contracts\ncompletion: Every contract has a result.\n"
                + "validation: Confirm findings cite inputs.\npermission: NONE\nrisk: LOW\n";
    }

    private void withFixture(ThrowingConsumer<Fixture> test) throws Exception {
        Path base = Files.createTempDirectory("acw-http-test-");
        try {
            Path workspace = Files.createDirectory(base.resolve("workspace"));
            Path state = Files.createDirectory(base.resolve("state"));
            Path target = Files.createDirectories(
                    workspace.resolve(".agents/skills/review-api-change")).resolve("SKILL.md");
            Files.writeString(target, "old skill body\n", StandardCharsets.UTF_8);
            try (LocalWorkbenchServer server = LocalWorkbenchServer.start(state)) {
                test.accept(new Fixture(server, workspace, state, target));
            }
        } finally {
            deleteOwned(base);
        }
    }

    private record Fixture(LocalWorkbenchServer server, Path workspace, Path state, Path target) {
        URI endpoint(String action) {
            return server.baseUri().resolve("api/v1/skill-changes/" + action);
        }
        String origin() {
            String value = server.baseUri().toString();
            return value.substring(0, value.length() - 1);
        }
        URI inventoryEndpoint() {
            return server.baseUri().resolve("api/v1/skills/inventory");
        }
        HttpResponse<String> postInventory(String body) throws Exception {
            return postInventory(body, server.sessionToken(), origin());
        }
        HttpResponse<String> postInventory(String body, String token,
                String suppliedOrigin) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(inventoryEndpoint())
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Origin", suppliedOrigin);
            if (token != null) builder.header("Authorization", "Bearer " + token);
            return HttpClient.newHttpClient().send(builder.POST(
                    HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        HttpResponse<String> post(String action, String body) throws Exception {
            return post(action, body, server.sessionToken(), origin());
        }
        HttpResponse<String> postWithType(String action, String body, String contentType)
                throws Exception {
            HttpRequest request = HttpRequest.newBuilder(endpoint(action))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", contentType)
                    .header("Origin", origin())
                    .header("Authorization", "Bearer " + server.sessionToken())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        HttpResponse<String> post(String action, String body, String token,
                String suppliedOrigin) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(action))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Origin", suppliedOrigin);
            if (token != null) builder.header("Authorization", "Bearer " + token);
            return HttpClient.newHttpClient().send(builder.POST(
                    HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(character);
            }
        }
        return result.append('"').toString();
    }

    private static String capture(String text, String expression) {
        Matcher matcher = Pattern.compile(expression).matcher(text);
        if (!matcher.find()) throw new AssertionError("missing pattern in " + text);
        return matcher.group(1);
    }

    private static void deleteOwned(Path root) throws IOException {
        if (!root.getFileName().toString().startsWith("acw-http-test-")
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("unsafe cleanup root");
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void run(String name, ThrowingRunnable test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void contains(String value, String expected) {
        if (!value.contains(expected)) throw new AssertionError("missing " + expected + " in " + value);
    }
    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
    @FunctionalInterface private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }
}
