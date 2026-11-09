package consulo.httpClient.cli;

import consulo.httpClient.execution.runner.HttpRequestRunResult;
import org.javamaster.httpclient.impl.js.HttpScriptTestResult;
import org.javamaster.httpclient.model.HttpResponse;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * Prints run progress to the console.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public final class HttpCliReporter {
    private final PrintStream myOut;
    private final LogLevel myLogLevel;
    private final boolean myStacktrace;

    public HttpCliReporter(PrintStream out, LogLevel logLevel, boolean stacktrace) {
        myOut = out;
        myLogLevel = logLevel;
        myStacktrace = stacktrace;
    }

    public void fileStarted(String fileName) {
        myOut.println("=== " + fileName);
    }

    public void fileFailed(String fileName, String error, Throwable cause) {
        myOut.println("ERROR " + fileName + ": " + error);
        if (myStacktrace) {
            cause.printStackTrace(myOut);
        }
    }

    public void requestFinished(HttpRequestRunResult result) {
        myOut.println();
        myOut.println("### " + result.tabName().get());

        if (myLogLevel == LogLevel.VERBOSE) {
            printLines(result.httpInfo().getHttpReqDescList());
            myOut.println();
            printLines(result.httpInfo().getHttpResDescList());
            myOut.println();
        }
        else {
            myOut.println(result.method() + " " + (result.url() == null ? "" : result.url()));
            HttpResponse response = result.response();
            if (myLogLevel == LogLevel.HEADERS && response != null) {
                for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
                    for (String value : entry.getValue()) {
                        myOut.println("  " + entry.getKey() + ": " + value);
                    }
                }
            }
        }

        HttpResponse response = result.response();
        if (response != null) {
            myOut.println("<- " + response.statusCode() + " (" + result.costMillis() + " ms)"
                + (result.savedFile() == null ? "" : ", saved to " + result.savedFile()));
        }
        Throwable error = result.error();
        if (error != null) {
            myOut.println("ERROR: " + describe(error));
            if (myStacktrace) {
                error.printStackTrace(myOut);
            }
        }

        for (HttpScriptTestResult test : result.tests()) {
            if (test.passed()) {
                myOut.println("  [PASSED] " + test.name());
            }
            else {
                myOut.println("  [FAILED] " + test.name() + (test.message() == null ? "" : ": " + test.message()));
            }
        }
    }

    public void summary(int requests, int failedRequests, int tests, int failedTests) {
        myOut.println();
        myOut.println("Requests: " + requests + ", failed: " + failedRequests
            + (tests == 0 ? "" : "; tests: " + tests + ", failed: " + failedTests));
    }

    private void printLines(List<String> lines) {
        for (String line : lines) {
            myOut.print(line.replace("\r\n", "\n"));
        }
    }

    static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : current.getClass().getSimpleName() + ": " + message;
    }
}
