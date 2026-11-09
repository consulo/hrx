package consulo.httpClient.cli;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests of the command line client. The same tests run in process ({@code gradle test}),
 * against the installed distribution ({@code gradle distributionTest}) and against the native binary
 * ({@code gradle nativeCliTest}), see {@link HttpCliRunner}.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public class HttpCliTest {
    private static EchoServer ourServer;

    @TempDir
    Path myDir;

    @BeforeAll
    public static void startServer() throws Exception {
        ourServer = new EchoServer();
        System.out.println("CLI under test: " + HttpCliRunner.mode());
    }

    @AfterAll
    public static void stopServer() {
        ourServer.close();
    }

    private Path write(String name, String content) throws Exception {
        Path file = myDir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.replace("$HOST", ourServer.url()), StandardCharsets.UTF_8);
        return file;
    }

    private HttpCliRunner.Result run(String... args) throws Exception {
        HttpCliRunner.Result result = HttpCliRunner.run(myDir, args);
        System.out.println(result.output());
        return result;
    }

    @Test
    public void testVersionAndHelp() throws Exception {
        HttpCliRunner.Result version = run("-v");
        assertEquals(0, version.exitCode());
        assertTrue(version.output().startsWith("hrx "), version.output());

        assertEquals(0, run("--version").exitCode());

        HttpCliRunner.Result help = run("-h");
        assertEquals(0, help.exitCode());
        assertTrue(help.output().contains("--env-variables"), help.output());
    }

    @Test
    public void testInvalidArguments() throws Exception {
        assertEquals(2, run().exitCode());
        assertEquals(2, run("--unknown-option", "a.http").exitCode());
    }

    @Test
    public void testSimpleRequest() throws Exception {
        Path file = write("simple.http", """
            GET $HOST/echo/simple?a=1
            Accept: application/json
            """);

        HttpCliRunner.Result result = run(file.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("GET " + ourServer.url() + "/echo/simple?a=1"), result.output());
        assertTrue(result.output().contains("<- 200"), result.output());
        assertTrue(result.output().contains("Requests: 1, failed: 0"), result.output());
    }

    @Test
    public void testEnvironmentAndVariables() throws Exception {
        write("http-client.env.json", """
            {
              "common": {"prefix": "/echo"},
              "dev": {"host": "$HOST", "user": "dev-user"},
              "prod": {"host": "https://example.invalid"}
            }
            """);
        write("http-client.private.env.json", """
            {"dev": {"token": "secret"}}
            """);
        Path file = write("env.http", """
            @name = {{user}}

            ### check
            POST {{host}}{{prefix}}/env
            Content-Type: application/json
            Authorization: Bearer {{token}}
            X-Override: {{override}}

            {"name": "{{name}}", "uuid": "{{$random.uuid}}"}

            > {%
              client.test("variables resolved", function () {
                client.assert(response.body.headers["authorization"] === "Bearer secret", "token: " + response.body.headers["authorization"]);
                client.assert(response.body.headers["x-override"] === "from-cli", "override: " + response.body.headers["x-override"]);
                var sent = JSON.parse(response.body.body);
                client.assert(sent.name === "dev-user", "name: " + sent.name);
                client.assert(sent.uuid.length === 32, "uuid: " + sent.uuid);
              });
            %}
            """);

        HttpCliRunner.Result result = run("-e", "dev", "-V", "override=from-cli", file.toString());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[PASSED] variables resolved"), result.output());
    }

    @Test
    public void testUnknownEnvironment() throws Exception {
        write("http-client.env.json", """
            {"dev": {"host": "$HOST"}}
            """);
        Path file = write("env.http", "GET {{host}}/echo\n");

        HttpCliRunner.Result result = run("-e", "qa", file.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("Environment 'qa' is not defined"), result.output());
    }

    @Test
    public void testScriptsGlobalsAndReport() throws Exception {
        Path file = write("scripts.http", """
            ### create
            < {%
              request.variables.set("requestId", "r-" + 7);
            %}
            POST $HOST/echo/users
            X-Request: {{requestId}}

            {"a": 1}

            > {%
              client.test("created", function () {
                client.assert(response.status === 201, "status " + response.status);
                client.assert(response.contentType.mimeType === "application/json", "mime");
                client.assert(response.body.headers["x-request"] === "r-7", "pre-request variable");
              });
              client.global.set("userId", response.body.id);
              client.log("created", response.body.id);
            %}

            ### read
            GET $HOST/echo/users/{{userId}}

            > {%
              client.test("global from previous request", function () {
                client.assert(response.body.path === "/echo/users/42", "path " + response.body.path);
              });
              client.test("fails on purpose", function () {
                client.assert(false, "expected failure");
              });
            %}
            """);

        HttpCliRunner.Result result = run("-r", myDir.resolve("reports").toString(), file.toString());

        assertEquals(1, result.exitCode(), "failed test must fail the run");
        assertTrue(result.output().contains("[PASSED] created"), result.output());
        assertTrue(result.output().contains("[PASSED] global from previous request"), result.output());
        assertTrue(result.output().contains("[FAILED] fails on purpose: expected failure"), result.output());
        assertTrue(result.output().contains("tests: 3, failed: 1"), result.output());

        String report = Files.readString(myDir.resolve("reports/report.xml"));
        assertTrue(report.contains("<testsuites tests=\"3\" failures=\"1\" errors=\"0\""), report);
        assertTrue(report.contains("name=\"read / fails on purpose\""), report);
        assertTrue(report.contains("<failure message=\"expected failure\"/>"), report);
    }

    @Test
    public void testBodyFromFileAndOutput() throws Exception {
        write("data/body.json", "{\"user\": \"{{who}}\"}\n");
        Path file = write("files.http", """
            ### upload
            POST $HOST/echo/upload
            Content-Type: application/json

            < ./data/body.json

            >> out/response.json
            """);

        HttpCliRunner.Result result = run("-V", "who=file-user", file.toString());

        assertEquals(0, result.exitCode(), result.output());
        Path saved = myDir.resolve("out/response.json");
        assertTrue(Files.exists(saved), result.output());
        String response = Files.readString(saved);
        assertTrue(response.contains("file-user"), response);
        assertTrue(response.contains("/echo/upload"), response);
    }

    @Test
    public void testRequestFilterAndVerboseOutput() throws Exception {
        Path file = write("filter.http", """
            ### first
            GET $HOST/echo/first

            ### second
            GET $HOST/text
            """);

        HttpCliRunner.Result result = run("-n", "second", "-L", "VERBOSE", file.toString());

        assertEquals(0, result.exitCode(), result.output());
        assertFalse(result.output().contains("/echo/first"), result.output());
        assertTrue(result.output().contains("plain hello"), result.output());
        // localized texts come from LOCALIZE-LIB yaml, not raw keys
        assertTrue(result.output().contains("// Size: 0 B"), result.output());
        assertFalse(result.output().contains("HttpClientLocalize@"), result.output());
    }

    @Test
    public void testConnectionErrorAndFailFast() throws Exception {
        Path file = write("errors.http", """
            ### unreachable
            GET http://127.0.0.1:1/nothing

            ### never sent
            GET $HOST/echo/never
            """);

        HttpCliRunner.Result result = run("--fail-fast", "-c", "2000", file.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("ERROR:"), result.output());
        assertFalse(result.output().contains("/echo/never"), result.output());
    }

    @Test
    public void testErrorStatusIsNotAFailure() throws Exception {
        Path file = write("status.http", "GET $HOST/status/404\n");

        HttpCliRunner.Result result = run(file.toString());

        assertEquals(0, result.exitCode(), "a 404 without tests is still a completed request");
        assertTrue(result.output().contains("<- 404"), result.output());
    }

    @Test
    public void testScriptHelpers() throws Exception {
        write("js/utils.js", """
            var MyUtils = {
              isBlank: function (text) { return text === null || String(text).trim() === ""; }
            };
            """);
        write("js/module.js", """
            module.exports = { twice: function (n) { return n * 2; } };
            """);
        write("data/text.txt", "file content");
        Path file = write("helpers.http", """
            @q = 1

            ### json
            < {%
              client.global.set("rawUrl", request.url.getRaw());
              client.global.set("substitutedUrl", request.url.tryGetSubstituted());
            %}
            POST $HOST/echo/items?q={{q}}
            Content-Type: application/json

            {"items": [{"id": 1, "tags": ["a", "b"]}, {"id": 2, "tags": ["c"]}], "meta": {"id": 9}}

            > {%
              client.test("request.url", function () {
                client.assert(client.global.get("rawUrl") === "$HOST/echo/items?q={{q}}", "raw: " + client.global.get("rawUrl"));
                client.assert(client.global.get("substitutedUrl") === "$HOST/echo/items?q=1", "substituted: " + client.global.get("substitutedUrl"));
              });
              client.test("jsonPath", function () {
                var sent = JSON.parse(response.body.body);
                client.assert(jsonPath(sent, "$.items[1].id") === 2, "index");
                client.assert(jsonPath(sent, "$.items[*].id").join(",") === "1,2", "wildcard");
                client.assert(jsonPath(sent, "$..id").join(",") === "1,2,9", "deep: " + jsonPath(sent, "$..id"));
                client.assert(jsonPath(sent, "$['meta'].id") === 9, "bracket");
                client.assert(jsonPath(response.body.body, "$.items[0].tags[-1]") === "b", "string input, negative index");
                client.assert(response.body.jsonPath.evaluate("$.id") === 42, "response.body.jsonPath");
              });
              client.test("crypto and base64", function () {
                client.assert(CryptoJS.MD5("abc").toString() === "900150983cd24fb0d6963f7d28e17f72", "md5");
                client.assert(CryptoJS.SHA256("abc").toString() === "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "sha256");
                client.assert(btoa("hello") === "aGVsbG8=", "btoa");
                client.assert(Window.atob("aGVsbG8=") === "hello", "atob");
              });
              client.test("files and modules", function () {
                client.assert(readString("./data/text.txt") === "file content", "readString");
                client.assert(require("./js/utils.js").MyUtils.isBlank("  "), "require top level vars");
                client.assert(require("./js/module.js").twice(21) === 42, "require module.exports");
                base64ToFile(btoa("saved"), "out/saved.txt");
              });
              client.test("URLSearchParams", function () {
                var params = new URLSearchParams("a=1&b=x%20y");
                params.append("c", "3");
                client.assert(params.get("b") === "x y", "get");
                client.assert(params.toString() === "a=1&b=x%20y&c=3", "toString: " + params.toString());
              });
            %}

            ### xml
            GET $HOST/xml

            > {%
              client.test("xpath", function () {
                client.assert(response.body.xpath.evaluate("/slideshow/slide[2]/title") === "Second", "response.body.xpath");
                client.assert(xpath(response.body, "count(//slide)") === "2", "xpath()");
                client.assert(response.body.indexOf("<slideshow>") === 0, "body is still text");
              });
            %}
            """);

        HttpCliRunner.Result result = run(file.toString());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("tests: 6, failed: 0"), result.output());
        assertEquals("saved", Files.readString(myDir.resolve("out/saved.txt")));
    }

    @Test
    public void testUrlForms() throws Exception {
        Path file = write("urls.http", """
            @base = $HOST
            @path = /echo/items
            @id = 7

            ### variable right after the port
            GET $HOST{{path}}

            > {%
              client.test("port + variable", function () { client.assert(response.body.path === "/echo/items", response.body.path); });
            %}

            ### text and variable in one segment
            GET $HOST/echo/user-{{id}}/x{{id}}y

            > {%
              client.test("mixed segment", function () { client.assert(response.body.path === "/echo/user-7/x7y", response.body.path); });
            %}

            ### variable path after variable host, with query
            GET {{base}}{{path}}/{{id}}?q={{id}}

            > {%
              client.test("variables only", function () { client.assert(response.body.path === "/echo/items/7" && response.body.query === "q=7", response.body.path + "?" + response.body.query); });
            %}

            ### classic form still works
            GET $HOST/echo/{{id}}/tail

            > {%
              client.test("classic", function () { client.assert(response.body.path === "/echo/7/tail", response.body.path); });
            %}
            """);

        HttpCliRunner.Result result = run(file.toString());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("tests: 4, failed: 0"), result.output());
    }

    @Test
    public void testInlineHandlers() throws Exception {
        Path file = write("inline.http", """
            <! {% client.global.set("fromGlobal", "g"); %}

            ### without body
            < {% request.variables.set("pre", "p"); %}
            GET $HOST/echo/{{fromGlobal}}/{{pre}}

            > {% client.test("inline after headers", function () { client.assert(response.body.path === "/echo/g/p", response.body.path); }); %}

            ### with body
            POST $HOST/echo/body
            Content-Type: application/json

            {"a": 1}

            > {% client.test("inline after body", function () { client.assert(JSON.parse(response.body.body).a === 1, response.body.body); }); %}

            ### multi-line still works
            GET $HOST/echo/multi

            > {%
              client.test("multi-line", function () { client.assert(response.status === 200, "status"); });
            %}
            """);

        HttpCliRunner.Result result = run(file.toString());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("tests: 3, failed: 0"), result.output());
    }

    /**
     * Needs a running remote agent, for example the podman container: {@code HRX_TEST_AGENT=127.0.0.1:57639}.
     * The agent reaches the test server via {@code HRX_TEST_AGENT_HOST} (default {@code host.containers.internal}).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "HRX_TEST_AGENT", matches = ".+")
    public void testRemoteAgent() throws Exception {
        String agentHost = System.getenv().getOrDefault("HRX_TEST_AGENT_HOST", "host.containers.internal");
        try (EchoServer server = new EchoServer("0.0.0.0")) {
            Path file = write("agent.http", """
                POST http://{{agentHost}}:%d/echo/agent
                Content-Type: application/json

                {"from": "hrx"}

                > {%%
                  client.test("sent by the agent", function () {
                    client.assert(response.body.client !== "127.0.0.1", "request came from this machine: " + response.body.client);
                    client.assert(JSON.parse(response.body.body).from === "hrx", "body: " + response.body.body);
                  });
                %%}
                """.formatted(server.port()));

            HttpCliRunner.Result result = run("--agent", System.getenv("HRX_TEST_AGENT"), "-V", "agentHost=" + agentHost, file.toString());

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("Sending requests from Remote"), result.output());
            assertTrue(result.output().contains("[PASSED] sent by the agent"), result.output());
        }
    }

    @Test
    public void testRemoteAgentNotReachable() throws Exception {
        Path file = write("agent.http", "GET http://example.invalid/\n");

        HttpCliRunner.Result result = run("--agent", "127.0.0.1:1", file.toString());

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("ERROR: Failed to connect to remote agent at 127.0.0.1:1"), result.output());
    }
}
