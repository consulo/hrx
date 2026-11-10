package consulo.httpClient.cli;

import consulo.application.Application;
import consulo.component.internal.ComponentBinding;
import consulo.component.internal.inject.*;
import consulo.disposer.Disposable;
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

    private HttpCliApplication(Disposable lastDisposable, ComponentBinding componentBinding) {
        super(lastDisposable, componentBinding, new LightApplicationBuilder.DefaultRegistrator());
    }

    /**
     * Same as {@link LightApplicationBuilder#build()}, creating this class instead of {@link LightApplication}.
     */
    public static Application create(Disposable rootDisposable) {
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
        return new HttpCliApplication(rootDisposable, new ComponentBinding(injectingBindingLoader, topicBindingLoader));
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
