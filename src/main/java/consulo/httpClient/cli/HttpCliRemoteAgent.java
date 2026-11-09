package consulo.httpClient.cli;

import consulo.platform.Platform;
import consulo.remoteServer.agent.impl.internal.RemoteAgentConnection;
import consulo.remoteServer.agent.impl.internal.nio.RemoteFileSystemProvider;
import consulo.remoteServer.agent.impl.internal.nio.RemoteNioFileSystem;
import consulo.remoteServer.agent.impl.internal.platform.RemotePlatform;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connection to a Consulo remote agent ({@code --agent}): requests are sent by the agent host, through the
 * platform's {@code RemoteAgentHttpRequestSender}.
 *
 * @author VISTALL
 * @since 2026-11-08
 */
public final class HttpCliRemoteAgent implements AutoCloseable {
    private final ExecutorService myExecutor;
    private final RemoteAgentConnection myConnection;
    private final RemoteNioFileSystem myFileSystem;
    private final RemotePlatform myPlatform;

    private HttpCliRemoteAgent(ExecutorService executor, RemoteAgentConnection connection, RemoteNioFileSystem fileSystem) {
        myExecutor = executor;
        myConnection = connection;
        myFileSystem = fileSystem;
        myPlatform = new RemotePlatform(connection, fileSystem);
    }

    /**
     * @param address     {@code host:port}
     * @param fingerprint SHA-256 fingerprint of the agent certificate, empty to trust the system certificates
     */
    public static HttpCliRemoteAgent connect(String address, boolean tls, String fingerprint, @Nullable String token) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new IllegalArgumentException("--agent must be HOST:PORT, got: " + address);
        }
        String host = address.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(address.substring(colon + 1));
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("--agent port is not a number: " + address);
        }

        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "hrx-agent");
            thread.setDaemon(true);
            return thread;
        });
        RemoteAgentConnection connection = new RemoteAgentConnection(host, port, tls, fingerprint, token, executor);
        try {
            connection.connect();
            RemoteNioFileSystem fileSystem = new RemoteFileSystemProvider().newFileSystem(connection);
            return new HttpCliRemoteAgent(executor, connection, fileSystem);
        }
        catch (RuntimeException e) {
            connection.close();
            executor.shutdownNow();
            throw e;
        }
    }

    public Platform getPlatform() {
        return myPlatform;
    }

    public String getName() {
        return myPlatform.getName();
    }

    @Override
    public void close() {
        try {
            myFileSystem.close();
        }
        catch (IOException | RuntimeException ignored) {
            // connection is closed below anyway
        }
        myConnection.close();
        myExecutor.shutdownNow();
    }
}
