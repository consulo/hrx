package consulo.httpClient.cli;

/**
 * @author VISTALL
 * @since 2026-11-03
 */
public enum LogLevel {
    /**
     * Request line, status and test results.
     */
    BASIC,
    /**
     * Plus request and response headers.
     */
    HEADERS,
    /**
     * Plus bodies and script output.
     */
    VERBOSE
}
