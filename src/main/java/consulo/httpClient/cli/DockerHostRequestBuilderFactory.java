package consulo.httpClient.cli;

import consulo.http.HttpMethod;
import consulo.http.HttpRequestBuilder;
import consulo.http.HttpRequestBuilderFactory;
import consulo.platform.Platform;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * {@code --docker-mode}: requests to {@code localhost} go to {@code host.docker.internal}, the host machine of a container.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public final class DockerHostRequestBuilderFactory implements HttpRequestBuilderFactory {
    private final HttpRequestBuilderFactory myDelegate;

    public DockerHostRequestBuilderFactory(HttpRequestBuilderFactory delegate) {
        myDelegate = delegate;
    }

    @Override
    public HttpRequestBuilder newBuilder(Platform platform, String url, HttpMethod httpMethod) {
        return myDelegate.newBuilder(platform, toDockerHost(url), httpMethod);
    }

    static String toDockerHost(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
                return url;
            }
            return new URI(uri.getScheme(), uri.getRawUserInfo(), "host.docker.internal", uri.getPort(), uri.getRawPath(),
                uri.getRawQuery(), uri.getRawFragment()).toString();
        }
        catch (URISyntaxException e) {
            return url;
        }
    }
}
