package consulo.httpClient.cli;

import consulo.application.Application;
import consulo.component.internal.ComponentBinding;
import consulo.component.internal.inject.*;
import consulo.disposer.Disposable;
import consulo.http.HttpCertificateManager;
import consulo.http.HttpProxyManager;
import consulo.http.HttpRequestBuilderFactory;
import consulo.httpClient.cli.http.HttpRequestBuilderFactoryImpl;
import consulo.test.light.LightApplicationBuilder;
import consulo.test.light.impl.LightApplication;
import consulo.util.lang.function.ThrowableSupplier;
import consulo.util.lang.ref.SimpleReference;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Light application which knows whether the current thread is inside a read action. The plain light application
 * always reports read access, and the platform http stack refuses network access inside read actions.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public final class HttpCliApplication extends LightApplication {
    private final ThreadLocal<int[]> myReadActionDepth = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * Assigned after the super constructor, which registers services; the providers below are lazy.
     */
    private HttpProxyManager myProxyManager;
    private HttpCertificateManager myCertificateManager;

    private HttpCliApplication(Disposable lastDisposable,
                               ComponentBinding componentBinding,
                               HttpProxyManager proxyManager,
                               HttpCertificateManager certificateManager) {
        super(lastDisposable, componentBinding, new LightApplicationBuilder.DefaultRegistrator());
        myProxyManager = proxyManager;
        myCertificateManager = certificateManager;
    }

    /**
     * Http services of the platform, implemented by the command line client (consulo-http-impl is not on the class path).
     */
    @Override
    protected void registerServices(InjectingContainerBuilder builder) {
        super.registerServices(builder);

        builder.bind(HttpProxyManager.class).forceSingleton().to(() -> myProxyManager);
        builder.bind(HttpCertificateManager.class).forceSingleton().to(() -> myCertificateManager);
        builder.bind(HttpRequestBuilderFactory.class).forceSingleton()
            .to(() -> new HttpRequestBuilderFactoryImpl(this, myProxyManager, myCertificateManager));
    }

    /**
     * Same as {@link LightApplicationBuilder#build()}, creating this class instead of {@link LightApplication}.
     */
    public static Application create(Disposable rootDisposable, HttpProxyManager proxyManager, HttpCertificateManager certificateManager) {
        NewInjectingBindingCollector injectingBindingCollector = new NewInjectingBindingCollector();
        NewTopicBindingCollector topicBindingCollector = new NewTopicBindingCollector();
        NewBindingLoader loader = new NewBindingLoader(injectingBindingCollector, topicBindingCollector);
        List<Runnable> actions = new ArrayList<>();
        loader.init(actions);
        actions.parallelStream().forEach(Runnable::run);

        InjectingBindingLoader injectingBindingLoader = new InjectingBindingLoader(
            injectingBindingCollector.getServices(),
            injectingBindingCollector.getExtensions(),
            injectingBindingCollector.getTopics(),
            injectingBindingCollector.getActions()
        );
        TopicBindingLoader topicBindingLoader = new TopicBindingLoader(topicBindingCollector.getBindings());
        return new HttpCliApplication(rootDisposable, new ComponentBinding(injectingBindingLoader, topicBindingLoader), proxyManager,
            certificateManager);
    }

    @Override
    public boolean isReadAccessAllowed() {
        return myReadActionDepth.get()[0] > 0;
    }

    @Override
    public void runReadAction(Runnable action) {
        int[] depth = myReadActionDepth.get();
        depth[0]++;
        try {
            action.run();
        }
        finally {
            depth[0]--;
        }
    }

    @Override
    public <T> T runReadAction(Supplier<T> computation) {
        int[] depth = myReadActionDepth.get();
        depth[0]++;
        try {
            return computation.get();
        }
        finally {
            depth[0]--;
        }
    }

    @Override
    public <T, E extends Throwable> T runReadAction(ThrowableSupplier<T, E> computation) throws E {
        int[] depth = myReadActionDepth.get();
        depth[0]++;
        try {
            return computation.get();
        }
        finally {
            depth[0]--;
        }
    }

    @Override
    public boolean tryRunReadAction(Runnable action) {
        runReadAction(action);
        return true;
    }

    @Override
    public <T, E extends Throwable> boolean tryRunReadAction(SimpleReference<T> ref, ThrowableSupplier<T, E> computation) throws E {
        ref.set(runReadAction(computation));
        return true;
    }
}
