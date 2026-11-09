package consulo.httpClient.cli;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the CLI in the mode selected by the {@code httpCli.executable} system property: an external executable
 * (installed start script or native binary) when set, in process otherwise.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public final class HttpCliRunner {
    public record Result(int exitCode, String output) {
    }

    public static final String EXECUTABLE_PROPERTY = "httpCli.executable";

    private HttpCliRunner() {
    }

    public static String mode() {
        String executable = System.getProperty(EXECUTABLE_PROPERTY);
        return executable == null || executable.isBlank() ? "in-process" : executable;
    }

    public static Result run(Path workingDirectory, String... args) throws Exception {
        String executable = System.getProperty(EXECUTABLE_PROPERTY);
        if (executable == null || executable.isBlank()) {
            return runInProcess(args);
        }
        return runExternal(Path.of(executable), workingDirectory, args);
    }

    private static synchronized Result runInProcess(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        try {
            System.setOut(capture);
            System.setErr(capture);
            int exitCode = new CommandLine(new HttpCliCommand()).execute(args);
            return new Result(exitCode, buffer.toString(StandardCharsets.UTF_8));
        }
        finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    private static Result runExternal(Path executable, Path workingDirectory, String... args) throws IOException, InterruptedException {
        if (!Files.isExecutable(executable)) {
            throw new IllegalStateException("Not executable: " + executable + " (build it first)");
        }

        List<String> command = new ArrayList<>();
        command.add(executable.toAbsolutePath().toString());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timeout: " + command);
        }
        return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }
}
