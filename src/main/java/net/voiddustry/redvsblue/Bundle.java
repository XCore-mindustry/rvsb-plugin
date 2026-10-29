package net.voiddustry.redvsblue;

import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.Structs;
import mindustry.Vars;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

@SuppressWarnings("unused")
public class Bundle {
    private static final ObjectMap<Locale, ResourceBundle> bundles = new ObjectMap<>();
    private static final ObjectMap<Locale, MessageFormat> formats = new ObjectMap<>();

    public static final Locale defaultLocale = new Locale("en");
    public static final Locale[] supportedLocales;

    private Bundle() {}

    static {
        Fi[] files = Vars.mods.getMod("red-vs-blue").root.child("bundles").list();
        supportedLocales = new Locale[files.length];

        for (int i = 0; i < files.length; i++) {
            String code = files[i].nameWithoutExtension();
            if (!code.contains("_"))
                supportedLocales[i] = Locale.ROOT;
            else {
                String[] codes;
                if ((codes = code.replace("bundle_", "").split("_")).length == 1)
                    supportedLocales[i] = new Locale(codes[0]);
                else supportedLocales[i] = new Locale(codes[0], codes[1]);
            }
        }
    }

    private static ResourceBundle getResource(Locale locale) {
        ResourceBundle bundle = bundles.get(locale);
        if (bundle == null) {
            if (Structs.contains(supportedLocales, locale))
                bundles.put(locale, bundle = ResourceBundle.getBundle("bundles.bundle", locale));
            else bundle = getResource(defaultLocale);
        }
        return bundle;
    }

    public static Locale findLocale(String localeString) {
        Locale locale = Structs.find(supportedLocales, l -> localeString.equals(l.toString()) || localeString.startsWith(l.toString()));
        return locale != null ? locale : defaultLocale;
    }

    public static String format(String key, Locale locale, Object... values){
        String pattern = get(key, locale);
        if (values.length == 0)
            return pattern;

        MessageFormat format = formats.get(locale);
        if (format == null)
            formats.put(locale, format = new MessageFormat(pattern, locale));
        else if (!Structs.contains(supportedLocales, locale))
            format = formats.get(defaultLocale, () -> new MessageFormat(pattern, defaultLocale));
        format.applyPattern(pattern);
        return format.format(values);
    }

    public static String format(String key, Object... values) {
        return format(key, defaultLocale, values);
    }

    public static String get(String key, Locale locale) {
        try {
            return getResource(locale).getString(key);
        } catch (MissingResourceException e) {
            Log.err("Key '@' doesn't exist in locale '@'", key, locale);
            return (locale == defaultLocale)? "?" + key + "?" : get(key);
        }
    }

    public static String get(String key) {
        return get(key, defaultLocale);
    }

    // ------------------------------------------------------------------ unit and block names
    //
    // Vanilla content is translated by the game itself, datapatch content by config/assets/bundles
    // (the server sends those files to every client, which merges them into its own bundle).
    // The plugin does not keep its own copies of these names.

    private static final ObjectMap<String, ObjectMap<String, String>> dataBundles = new ObjectMap<>();
    private static final ObjectMap<String, Long> dataBundleTimes = new ObjectMap<>();

    /** Keys of config/assets/bundles/{@code file}.properties, reloaded when the file changes. Empty if missing. */
    private static ObjectMap<String, String> dataBundle(String file) {
        Fi fi = Vars.dataDirectory.child("assets/bundles/" + file + ".properties");
        long time = fi.exists() ? fi.lastModified() : -1L;
        if (!dataBundles.containsKey(file) || dataBundleTimes.get(file, -2L) != time) {
            ObjectMap<String, String> map = new ObjectMap<>();
            if (time != -1L) {
                try (var reader = fi.reader("UTF-8")) {
                    arc.util.io.PropertiesUtils.load(map, reader);
                } catch (Exception e) {
                    Log.err("Could not read @: @", fi.path(), e.getMessage());
                }
            }
            dataBundles.put(file, map);
            dataBundleTimes.put(file, time);
        }
        return dataBundles.get(file);
    }

    /** Hidden copies that show up under the name of the unit they copy. */
    private static final ObjectMap<String, String> nameAliases = ObjectMap.of(
            "dp-serverminate-unit", "dp-terminate-unit"
    );

    private static String nameKey(mindustry.ctype.UnlockableContent content) {
        return content.getContentType().name() + "." + nameAliases.get(content.name, content.name) + ".name";
    }

    // The dedicated server has no translations of vanilla content (its names are the internal ones, "dagger").
    // The build copies the unit/block/item/liquid/status names out of the game's own bundles into
    // vanilla-names/bundle_<plugin locale>.properties, so chat messages can name them in each player's language.
    private static final ObjectMap<Locale, ObjectMap<String, String>> vanillaNames = new ObjectMap<>();

    private static ObjectMap<String, String> vanillaNames(Locale locale) {
        ObjectMap<String, String> map = vanillaNames.get(locale);
        if (map == null) {
            map = new ObjectMap<>();
            Fi fi = Vars.mods.getMod("red-vs-blue").root.child("vanilla-names").child("bundle_" + locale + ".properties");
            if (fi.exists()) {
                try (var reader = fi.reader("UTF-8")) {
                    arc.util.io.PropertiesUtils.load(map, reader);
                } catch (Exception e) {
                    Log.err("Could not read @: @", fi.path(), e.getMessage());
                }
            }
            vanillaNames.put(locale, map);
        }
        return map;
    }

    private static boolean isVanilla(mindustry.ctype.UnlockableContent content) {
        return content.minfo.mod == null;
    }

    /**
     * Name for UI that the client renders (menu buttons, server UI labels): a {@code @bundle.key} the
     * client translates into its own language, or the plain in-game name when the client has no key
     * for it (hidden datapatch units without a bundle entry). Must be the whole label text.
     */
    public static String clientName(mindustry.ctype.UnlockableContent content) {
        if (content == null) return "";
        String key = nameKey(content);
        if (isVanilla(content) || dataBundle("bundle").containsKey(key)) return "@" + key;
        return content.localizedName;
    }

    /** Name of a unit or block in the given language, for text the server builds (chat messages). */
    public static String contentName(mindustry.ctype.UnlockableContent content, Locale locale) {
        if (content == null) return "";
        String key = nameKey(content);
        if (!isVanilla(content)) {
            for (String file : new String[]{"bundle_" + locale, "bundle_" + locale.getLanguage(), "bundle"}) {
                String name = dataBundle(file).get(key);
                if (name != null) return name;
            }
        } else {
            String name = vanillaNames(locale).get(key, () -> vanillaNames(defaultLocale).get(key));
            if (name != null) return name;
        }
        // the dedicated server has no vanilla translations: its names are the internal ones ("dagger")
        return content.localizedName.equals(content.name) ? arc.util.Strings.capitalize(content.name) : content.localizedName;
    }

    public static String get(String key, String locale) {
        return get(key, findLocale(locale));
    }
}
