package consulo.httpClient.cli;

import consulo.http.HttpCertificateManager;
import consulo.http.ssl.HttpConfirmingTrustManagerHttp;

import javax.net.ssl.*;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Certificates for the platform http stack: the JVM trust store, or trust everything with {@code --insecure}.
 * There is nobody to ask about unknown certificates, so they are rejected.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public final class HttpCliCertificateManager implements HttpCertificateManager {
    private final HttpConfirmingTrustManagerHttp myTrustManager;
    private final SSLContext mySslContext;

    public HttpCliCertificateManager(boolean insecure) {
        if (insecure) {
            // java.net.http reads it once, when its first client is created
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        }

        myTrustManager = insecure ? new TrustAll() : new SystemTrust(systemTrustManager());
        try {
            mySslContext = SSLContext.getInstance("TLS");
            mySslContext.init(null, new TrustManager[]{myTrustManager}, new SecureRandom());
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SSLContext getSslContext() {
        return mySslContext;
    }

    @Override
    public SSLContext getSystemSslContext() {
        try {
            return SSLContext.getDefault();
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public HttpConfirmingTrustManagerHttp getTrustManager() {
        return myTrustManager;
    }

    @Override
    public KeyManager[] getDefaultKeyManagers() {
        return new KeyManager[0];
    }

    private static X509TrustManager systemTrustManager() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    return x509;
                }
            }
            throw new IllegalStateException("No X509TrustManager in the JVM trust store");
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private record SystemTrust(X509TrustManager delegate) implements HttpConfirmingTrustManagerHttp {
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, boolean addToKeyStore, boolean askUser)
            throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    private static final class TrustAll implements HttpConfirmingTrustManagerHttp {
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, boolean addToKeyStore, boolean askUser) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
