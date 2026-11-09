package consulo.httpClient.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Local server for the tests.
 * <ul>
 * <li>{@code /echo/**} answers JSON with the request method, path, query, headers (lower case) and body;
 * POST answers 201 with {@code "id": 42}</li>
 * <li>{@code /text} answers plain text</li>
 * <li>{@code /xml} answers a small XML document</li>
 * <li>{@code /status/NNN} answers status NNN</li>
 * </ul>
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public final class EchoServer implements AutoCloseable {
    private final HttpServer myServer;

    public EchoServer() throws IOException {
        this("127.0.0.1");
    }

    /**
     * @param bindAddress {@code 0.0.0.0} to be reachable from containers (remote agent tests)
     */
    public EchoServer(String bindAddress) throws IOException {
        myServer = HttpServer.create(new InetSocketAddress(bindAddress, 0), 0);
        myServer.createContext("/echo", EchoServer::echo);
        myServer.createContext("/text", exchange -> send(exchange, 200, "text/plain", "plain hello"));
        myServer.createContext("/xml", exchange -> send(exchange, 200, "application/xml",
            "<slideshow><slide><title>First</title></slide><slide><title>Second</title></slide></slideshow>"));
        myServer.createContext("/status", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            send(exchange, status, "text/plain", "status " + status);
        });
        myServer.start();
    }

    public int port() {
        return myServer.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + myServer.getAddress().getPort();
    }

    private static void echo(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        Map<String, String> headers = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            headers.put(entry.getKey().toLowerCase(), String.join(",", entry.getValue()));
        }

        StringBuilder json = new StringBuilder("{");
        json.append("\"id\":42,");
        json.append("\"client\":").append(quote(exchange.getRemoteAddress().getAddress().getHostAddress())).append(',');
        json.append("\"method\":").append(quote(exchange.getRequestMethod())).append(',');
        json.append("\"path\":").append(quote(exchange.getRequestURI().getPath())).append(',');
        json.append("\"query\":").append(quote(String.valueOf(exchange.getRequestURI().getRawQuery()))).append(',');
        json.append("\"body\":").append(quote(body)).append(',');
        json.append("\"headers\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        json.append("}}");

        int status = "POST".equals(exchange.getRequestMethod()) ? 201 : 200;
        send(exchange, status, "application/json", json.toString());
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                default -> builder.append(c);
            }
        }
        return builder.append('"').toString();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        myServer.stop(0);
    }
}
