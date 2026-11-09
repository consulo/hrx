package consulo.httpClient.cli;

import consulo.disposer.Disposable;
import consulo.localize.LocalizeKey;
import consulo.localize.LocalizeManager;
import consulo.localize.LocalizeManagerListener;
import consulo.localize.LocalizeValue;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * English texts from {@code LOCALIZE-LIB/en_US/*.yaml} resources on the classpath.
 * <p>
 * The light test application only prints localize keys, and the IDE implementation needs plugin indexes. Registered
 * via META-INF/services; the CLI jar is first on the class path, so this provider wins over the light one.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public class HttpCliLocalizeManager extends LocalizeManager {
    private static final Locale LOCALE = Locale.US;

    private final Map<String, Map<String, String>> myTexts = new ConcurrentHashMap<>();

    @Override
    public LocalizeValue fromStringKey(String localizeKeyInfo) {
        int at = localizeKeyInfo.indexOf('@');
        if (at <= 0) {
            return LocalizeValue.of(localizeKeyInfo);
        }
        return LocalizeKey.of(localizeKeyInfo.substring(0, at), localizeKeyInfo.substring(at + 1)).getValue();
    }

    @Override
    public LocalizeValue fromException(Throwable t) {
        return LocalizeValue.of(t.toString());
    }

    @Override
    public Map.Entry<Locale, String> getUnformattedText(LocalizeKey key) {
        String text = myTexts.computeIfAbsent(key.getLocalizeId(), HttpCliLocalizeManager::load).get(key.getKey());
        return Map.entry(LOCALE, text != null ? text : key.getKey());
    }

    @Override
    public @Nullable String getUnformattedText(LocalizeKey key, Locale locale) {
        return LOCALE.equals(locale) ? getUnformattedText(key).getValue() : null;
    }

    @Override
    public String formatText(String unformattedText, Locale locale, Object... args) {
        if (args.length == 0) {
            return unformattedText;
        }
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = args[i] instanceof LocalizeValue value ? value.get() : args[i];
        }
        try {
            return new MessageFormat(unformattedText, locale).format(values);
        }
        catch (IllegalArgumentException e) {
            return unformattedText + " " + Arrays.toString(values);
        }
    }

    /**
     * Reads the localize YAML format: {@code key:} lines followed by an indented {@code text: value}.
     */
    private static Map<String, String> load(String localizeId) {
        Map<String, String> texts = new HashMap<>();
        String resource = "LOCALIZE-LIB/en_US/" + localizeId + ".yaml";
        try (InputStream stream = HttpCliLocalizeManager.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                return texts;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String key = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.trim().startsWith("#")) {
                    continue;
                }
                if (!Character.isWhitespace(line.charAt(0)) && line.endsWith(":")) {
                    key = line.substring(0, line.length() - 1).trim();
                }
                else if (key != null && line.trim().startsWith("text:")) {
                    texts.put(key, unquote(line.trim().substring("text:".length()).trim()));
                }
            }
        }
        catch (IOException ignored) {
            // keys are shown instead of texts
        }
        return texts;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\n", "\n");
        }
        return value;
    }

    @Override
    public Locale getDefaultLocale() {
        return LOCALE;
    }

    @Override
    public Locale parseLocale(String localeText) {
        return Locale.forLanguageTag(localeText.replace('_', '-'));
    }

    @Override
    public void setLocale(@Nullable Locale locale, boolean fireEvents) {
    }

    @Override
    public Locale getLocale() {
        return LOCALE;
    }

    @Override
    public Locale getAutoDetectedLocale() {
        return LOCALE;
    }

    @Override
    public boolean isDefaultLocale() {
        return true;
    }

    @Override
    public Set<Locale> getAvailableLocales() {
        return Set.of(LOCALE);
    }

    @Override
    public void addListener(LocalizeManagerListener listener, Disposable disposable) {
    }

    @Override
    public byte getModificationCount() {
        return 0;
    }
}
