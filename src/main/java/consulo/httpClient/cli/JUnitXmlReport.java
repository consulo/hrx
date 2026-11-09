package consulo.httpClient.cli;

import consulo.httpClient.execution.runner.HttpRequestRunResult;
import org.javamaster.httpclient.impl.js.HttpScriptTestResult;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Run results in the JUnit XML format understood by CI servers: one {@code testsuite} per file, one
 * {@code testcase} per {@code client.test}, or per request when it has no tests.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public final class JUnitXmlReport {
    public record FileResult(String fileName, List<HttpRequestRunResult> requests, @Nullable String error, long durationMs) {
    }

    private JUnitXmlReport() {
    }

    public static void write(Path file, List<FileResult> results) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toXml(results), StandardCharsets.UTF_8);
    }

    public static String toXml(List<FileResult> results) {
        int tests = 0;
        int failures = 0;
        int errors = 0;
        long time = 0;

        StringBuilder suites = new StringBuilder();
        for (FileResult file : results) {
            StringBuilder cases = new StringBuilder();
            int[] counters = new int[3];

            if (file.error() != null) {
                counters[0]++;
                counters[2]++;
                cases.append("    <testcase name=\"").append(escape(file.fileName())).append("\" classname=\"")
                    .append(escape(file.fileName())).append("\" time=\"0\">\n");
                cases.append("      <error message=\"").append(escape(file.error())).append("\"/>\n");
                cases.append("    </testcase>\n");
            }

            for (HttpRequestRunResult request : file.requests()) {
                writeRequest(cases, file.fileName(), request, counters);
            }

            tests += counters[0];
            failures += counters[1];
            errors += counters[2];
            time += file.durationMs();

            suites.append("  <testsuite name=\"").append(escape(file.fileName()))
                .append("\" tests=\"").append(counters[0])
                .append("\" failures=\"").append(counters[1])
                .append("\" errors=\"").append(counters[2])
                .append("\" time=\"").append(seconds(file.durationMs())).append("\">\n");
            suites.append(cases);
            suites.append("  </testsuite>\n");
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuites tests=\"" + tests + "\" failures=\"" + failures + "\" errors=\"" + errors + "\" time=\"" + seconds(time) + "\">\n"
            + suites
            + "</testsuites>\n";
    }

    private static void writeRequest(StringBuilder out, String className, HttpRequestRunResult request, int[] counters) {
        String requestName = request.tabName().get();

        if (request.tests().isEmpty() || request.hasError()) {
            counters[0]++;
            out.append("    <testcase name=\"").append(escape(requestName)).append("\" classname=\"").append(escape(className))
                .append("\" time=\"").append(seconds(request.costMillis())).append("\">\n");
            if (request.hasError()) {
                counters[2]++;
                out.append("      <error message=\"").append(escape(HttpCliReporter.describe(request.error()))).append("\"/>\n");
            }
            out.append("    </testcase>\n");
        }

        for (HttpScriptTestResult test : request.tests()) {
            counters[0]++;
            out.append("    <testcase name=\"").append(escape(requestName + " / " + test.name())).append("\" classname=\"")
                .append(escape(className)).append("\" time=\"0\">\n");
            if (!test.passed()) {
                counters[1]++;
                out.append("      <failure message=\"").append(escape(test.message())).append("\"/>\n");
            }
            out.append("    </testcase>\n");
        }
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1000.0);
    }

    private static String escape(@Nullable String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                case '&' -> builder.append("&amp;");
                case '"' -> builder.append("&quot;");
                case '\n' -> builder.append("&#10;");
                case '\r' -> builder.append("&#13;");
                case '\t' -> builder.append("&#9;");
                default -> {
                    if (c >= 0x20) {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }
}
