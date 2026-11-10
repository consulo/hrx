package consulo.httpClient.cli;

import consulo.application.ReadAction;
import consulo.httpClient.execution.HttpFileContentReader;
import consulo.httpClient.execution.env.HttpEnvironment;
import consulo.httpClient.execution.runner.HttpRequestRunResult;
import consulo.httpClient.execution.runner.HttpRequestRunner;
import org.javamaster.httpclient.impl.js.HttpScriptTestResult;
import consulo.http.HttpRequestBuilderFactory;
import consulo.http.impl.internal.HttpRequestBuilderFactoryImpl;
import consulo.language.psi.util.PsiTreeUtil;
import org.javamaster.httpclient.parser.HttpFile;
import org.javamaster.httpclient.psi.HttpMethod;
import org.javamaster.httpclient.utils.HttpUtilsPart;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Runs requests from .http files.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
@Command(
    name = "hrx",
    versionProvider = HttpCliCommand.VersionProvider.class,
    sortOptions = false,
    description = "Executes requests from .http files, with environments, variables and handler scripts.%n"
        + "Exit code is 0 when all requests succeed and all tests pass, 1 otherwise, 2 on invalid arguments."
)
public class HttpCliCommand implements Callable<Integer> {
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help and exit")
    private boolean myHelp;

    // -v, not -V: -V is --env-variables
    @Option(names = {"-v", "--version"}, versionHelp = true, description = "Print version and exit")
    private boolean myVersion;

    @Parameters(arity = "1..*", paramLabel = "FILE", description = ".http files to execute")
    private List<Path> myFiles = new ArrayList<>();

    @Option(names = {"-e", "--env"}, paramLabel = "NAME", description = "Environment name from the env files")
    private @Nullable String myEnvironment;

    @Option(names = "--env-file", paramLabel = "FILE",
        description = "Public env file. Default: http-client.env.json next to each .http file")
    private @Nullable Path myEnvFile;

    @Option(names = "--private-env-file", paramLabel = "FILE",
        description = "Private env file. Default: http-client.private.env.json next to each .http file")
    private @Nullable Path myPrivateEnvFile;

    @Option(names = {"-V", "--env-variables"}, paramLabel = "KEY=VALUE", description = "Public variable, overrides env files")
    private Map<String, String> myPublicVariables = new LinkedHashMap<>();

    @Option(names = {"-P", "--private-env-variables"}, paramLabel = "KEY=VALUE", description = "Private variable, overrides everything")
    private Map<String, String> myPrivateVariables = new LinkedHashMap<>();

    @Option(names = {"-n", "--request"}, paramLabel = "NAME",
        description = "Run only requests with this name (### name). Can be repeated")
    private Set<String> myRequestNames = new LinkedHashSet<>();

    @Option(names = {"-L", "--log-level"}, paramLabel = "LEVEL", defaultValue = "BASIC",
        description = "Output: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}")
    private LogLevel myLogLevel = LogLevel.BASIC;

    @Option(names = {"-r", "--report"}, arity = "0..1", paramLabel = "DIR", fallbackValue = "reports",
        description = "Write a JUnit XML report to DIR/report.xml. Default DIR: ${FALLBACK-VALUE}")
    private @Nullable Path myReportDirectory;

    @Option(names = {"-c", "--connect-timeout"}, paramLabel = "MILLIS", description = "Connect timeout. Default: 30000")
    private long myConnectTimeoutMillis = 30_000;

    @Option(names = {"-s", "--socket-timeout"}, paramLabel = "MILLIS", description = "Read timeout. Default: 3600000")
    private long mySocketTimeoutMillis = 3_600_000;

    @Option(names = "--insecure", description = "Trust all certificates, skip host name verification")
    private boolean myInsecure;

    @Option(names = "--proxy", paramLabel = "[USER:PASSWORD@]HOST:PORT", description = "HTTP proxy")
    private @Nullable String myProxy;

    @Option(names = {"-D", "--docker-mode"}, description = "Send localhost requests to host.docker.internal")
    private boolean myDockerMode;

    @Option(names = "--agent", paramLabel = "HOST:PORT",
        description = "Send requests from a Consulo remote agent instead of this machine")
    private @Nullable String myAgent;

    @Option(names = "--agent-token", paramLabel = "TOKEN",
        description = "Remote agent token. Default: environment variable HRX_AGENT_TOKEN")
    private @Nullable String myAgentToken;

    @Option(names = "--agent-tls", description = "Connect to the remote agent with TLS")
    private boolean myAgentTls;

    @Option(names = "--agent-fingerprint", paramLabel = "SHA256",
        description = "Trust only the remote agent certificate with this SHA-256 fingerprint (implies --agent-tls)")
    private @Nullable String myAgentFingerprint;

    @Option(names = "--fail-fast", description = "Stop after the first failed request")
    private boolean myFailFast;

    @Option(names = "--stacktrace", description = "Print stack traces of errors")
    private boolean myStacktrace;

    @Override
    public Integer call() throws Exception {
        PrintStream out = System.out;
        HttpCliReporter reporter = new HttpCliReporter(out, myLogLevel, myStacktrace);

        List<JUnitXmlReport.FileResult> fileResults = new ArrayList<>();
        int requests = 0;
        int failedRequests = 0;
        int tests = 0;
        int failedTests = 0;

        HttpCliRemoteAgent remoteAgent = null;
        try (HttpCliLightPlatform platform = new HttpCliLightPlatform()) {
            // the platform http stack (consulo-http-impl) with proxy and certificates from the command line
            HttpRequestBuilderFactory requestBuilderFactory = new HttpRequestBuilderFactoryImpl(
                platform.getApplication(),
                new HttpCliProxyManager(myProxy),
                new HttpCliCertificateManager(myInsecure)
            );
            if (myDockerMode) {
                requestBuilderFactory = new DockerHostRequestBuilderFactory(requestBuilderFactory);
            }

            HttpRequestRunner runner = new HttpRequestRunner(platform.getProject(), requestBuilderFactory, HttpFileContentReader.DISK)
                .setDefaultTimeouts(toSeconds(myConnectTimeoutMillis), toSeconds(mySocketTimeoutMillis));

            try {
                remoteAgent = connectAgent();
            }
            catch (RuntimeException e) {
                // wrong address, token or certificate: one line, not a stack trace
                out.println("ERROR: " + (e.getMessage() == null ? HttpCliReporter.describe(e) : e.getMessage()));
                if (myStacktrace) {
                    e.printStackTrace(out);
                }
                return 1;
            }
            if (remoteAgent != null) {
                runner.setPlatform(remoteAgent.getPlatform());
                out.println("Sending requests from " + remoteAgent.getName());
            }

            boolean stopped = false;
            for (Path path : myFiles) {
                if (stopped) {
                    break;
                }

                long start = System.currentTimeMillis();
                String fileName = path.toString();
                reporter.fileStarted(fileName);

                String error = null;
                List<HttpRequestRunResult> results = new ArrayList<>();
                try {
                    HttpEnvironment environment = loadEnvironment(path);
                    HttpFile httpFile = Files.isRegularFile(path) ? platform.findHttpFile(path) : null;
                    if (httpFile == null) {
                        throw new IllegalArgumentException("Not an .http file: " + path);
                    }

                    for (HttpMethod method : findRequests(httpFile)) {
                        HttpRequestRunResult result = runner.run(method, myEnvironment, environment);
                        results.add(result);
                        reporter.requestFinished(result);

                        requests++;
                        if (result.hasError()) {
                            failedRequests++;
                        }
                        for (HttpScriptTestResult test : result.tests()) {
                            tests++;
                            if (!test.passed()) {
                                failedTests++;
                            }
                        }

                        if (myFailFast && !result.isSuccessful()) {
                            stopped = true;
                            break;
                        }
                    }
                }
                catch (Exception e) {
                    error = HttpCliReporter.describe(e);
                    reporter.fileFailed(fileName, error, e);
                    failedRequests++;
                    if (myFailFast) {
                        stopped = true;
                    }
                }

                fileResults.add(new JUnitXmlReport.FileResult(fileName, results, error, System.currentTimeMillis() - start));
            }
        }
        finally {
            if (remoteAgent != null) {
                remoteAgent.close();
            }
        }

        reporter.summary(requests, failedRequests, tests, failedTests);

        if (myReportDirectory != null) {
            Path reportFile = myReportDirectory.resolve("report.xml");
            JUnitXmlReport.write(reportFile, fileResults);
            out.println("Report: " + reportFile.toAbsolutePath().normalize());
        }

        return failedRequests == 0 && failedTests == 0 ? 0 : 1;
    }

    private @Nullable HttpCliRemoteAgent connectAgent() {
        if (myAgent == null) {
            return null;
        }
        String token = myAgentToken != null ? myAgentToken : System.getenv("HRX_AGENT_TOKEN");
        String fingerprint = myAgentFingerprint == null ? "" : myAgentFingerprint;
        return HttpCliRemoteAgent.connect(myAgent, myAgentTls || !fingerprint.isEmpty(), fingerprint, token);
    }

    private List<HttpMethod> findRequests(HttpFile httpFile) {
        return ReadAction.compute(() -> {
            List<HttpMethod> methods = new ArrayList<>();
            for (HttpMethod method : PsiTreeUtil.findChildrenOfType(httpFile, HttpMethod.class)) {
                if (myRequestNames.isEmpty() || myRequestNames.contains(requestName(method))) {
                    methods.add(method);
                }
            }
            return methods;
        });
    }

    private static String requestName(HttpMethod method) {
        return HttpUtilsPart.getTabName(method).get();
    }

    private HttpEnvironment loadEnvironment(Path httpFile) throws Exception {
        Path directory = httpFile.toAbsolutePath().getParent();
        Path envFile = myEnvFile != null ? myEnvFile : HttpEnvironment.findEnvFile(directory, false);
        Path privateEnvFile = myPrivateEnvFile != null ? myPrivateEnvFile : HttpEnvironment.findEnvFile(directory, true);
        return HttpEnvironment.load(envFile, privateEnvFile, myEnvironment, myPublicVariables, myPrivateVariables, true);
    }

    private static long toSeconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{
                "hrx " + HrxVersion.VERSION,
                "Licensed under the Apache License 2.0. Third-party software and licenses: see NOTICE.txt"
            };
        }
    }
}
