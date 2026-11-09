package consulo.httpClient.cli;

import consulo.http.HttpProxyManager;
import consulo.util.lang.Pair;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.*;
import java.util.List;

/**
 * Proxy settings from the command line ({@code --proxy [user:password@]host:port}) for the platform http stack.
 * Without {@code --proxy} all connections are direct.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public final class HttpCliProxyManager implements HttpProxyManager {
    private final @Nullable InetSocketAddress myAddress;
    private final @Nullable String myLogin;
    private final @Nullable String myPassword;
    private final ProxySelector mySelector;
    private final Authenticator myAuthenticator;

    public HttpCliProxyManager(@Nullable String proxy) {
        if (proxy == null || proxy.isBlank()) {
            myAddress = null;
            myLogin = null;
            myPassword = null;
        }
        else {
            URI uri = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
            myAddress = InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort());

            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                myLogin = colon < 0 ? userInfo : userInfo.substring(0, colon);
                myPassword = colon < 0 ? "" : userInfo.substring(colon + 1);
            }
            else {
                myLogin = null;
                myPassword = null;
            }
        }

        mySelector = myAddress == null ? HttpClientNoProxy.SELECTOR : ProxySelector.of(myAddress);
        myAuthenticator = new Authenticator() {
            @Override
            protected @Nullable PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY || myLogin == null) {
                    return null;
                }
                return new PasswordAuthentication(myLogin, myPassword == null ? new char[0] : myPassword.toCharArray());
            }
        };
    }

    private static final class HttpClientNoProxy extends ProxySelector {
        static final ProxySelector SELECTOR = new HttpClientNoProxy();

        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    }

    @Override
    public URLConnection openConnection(String location) throws IOException {
        Proxy proxy = myAddress == null ? Proxy.NO_PROXY : new Proxy(Proxy.Type.HTTP, myAddress);
        return URI.create(location).toURL().openConnection(proxy);
    }

    @Override
    public boolean isHttpProxyEnabled() {
        return myAddress != null;
    }

    @Override
    public boolean isPacProxyEnabled() {
        return false;
    }

    @Override
    public boolean isProxyAuthenticationEnabled() {
        return myLogin != null;
    }

    @Override
    public boolean isHttpProxyEnabledForUrl(@Nullable String url) {
        return myAddress != null;
    }

    @Override
    public void prepareURL(String url) {
    }

    @Override
    public String getProxyHost() {
        return myAddress == null ? "" : myAddress.getHostString();
    }

    @Override
    public int getProxyPort() {
        return myAddress == null ? 0 : myAddress.getPort();
    }

    @Override
    public String getProxyLogin() {
        return myLogin == null ? "" : myLogin;
    }

    @Override
    public String getPlainProxyPassword() {
        return myPassword == null ? "" : myPassword;
    }

    @Override
    public List<Pair<String, String>> getJvmProperties(boolean withAutodetection, @Nullable URI uri) {
        if (myAddress == null) {
            return List.of();
        }
        return List.of(
            Pair.create("http.proxyHost", getProxyHost()),
            Pair.create("http.proxyPort", String.valueOf(getProxyPort())),
            Pair.create("https.proxyHost", getProxyHost()),
            Pair.create("https.proxyPort", String.valueOf(getProxyPort()))
        );
    }

    @Override
    public List<String> getProxyExceptions() {
        return List.of();
    }

    @Override
    public boolean isRealProxy(Proxy proxy) {
        return !Proxy.NO_PROXY.equals(proxy);
    }

    @Override
    public ProxySelector getOnlyBySettingsSelector() {
        return mySelector;
    }

    @Override
    public ProxySelector getProxySelector() {
        return mySelector;
    }

    @Override
    public Authenticator getProxyAuthenticator() {
        return myAuthenticator;
    }

    @Override
    public @Nullable PasswordAuthentication getGenericPassword(String host, int port) {
        return null;
    }
}
