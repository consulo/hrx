package consulo.httpClient.cli;

import consulo.platform.Platform;
import consulo.platform.impl.PlatformBase;
import consulo.ui.UIAccess;

import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * The real machine: the light test platform reports os.name "light", so on Windows the platform reads POSIX file
 * attributes and every file looks invalid.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
public class HttpCliPlatform extends PlatformBase {
    public HttpCliPlatform() {
        super(Platform.LOCAL, Platform.LOCAL, currentJvmProperties());
    }

    private static Map<String, String> currentJvmProperties() {
        Map<String, String> properties = new HashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            String value = System.getProperty(name);
            if (value != null) {
                properties.put(name, value);
            }
        }
        return properties;
    }

    @Override
    public void openInBrowser(URL url) {
    }

    @Override
    public void openFileInFileManager(File file, UIAccess uiAccess) {
    }

    @Override
    public void openDirectoryInFileManager(File file, UIAccess uiAccess) {
    }
}
