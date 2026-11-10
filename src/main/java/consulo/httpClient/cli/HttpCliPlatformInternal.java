package consulo.httpClient.cli;

import consulo.platform.Platform;
import consulo.platform.internal.PlatformInternal;

/**
 * Registered via META-INF/services; the CLI jar is first on the class path, so this provider wins over the light one.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
public class HttpCliPlatformInternal extends PlatformInternal {
    @Override
    public Platform createCurrent() {
        return new HttpCliPlatform();
    }
}
