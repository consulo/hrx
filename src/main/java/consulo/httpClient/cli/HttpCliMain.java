package consulo.httpClient.cli;

import picocli.CommandLine;

/**
 * Entry point of the command line HTTP client.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public class HttpCliMain {
    public static void main(String[] args) {
        // Netty (remote agent connection) must not use sun.misc.Unsafe: JDK 24+ warns about it on every start
        if (System.getProperty("io.netty.noUnsafe") == null) {
            System.setProperty("io.netty.noUnsafe", "true");
        }

        int exitCode = new CommandLine(new HttpCliCommand()).execute(args);
        System.exit(exitCode);
    }
}
