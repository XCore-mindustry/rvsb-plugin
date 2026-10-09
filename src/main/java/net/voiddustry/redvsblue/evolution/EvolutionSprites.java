package net.voiddustry.redvsblue.evolution;

import arc.Events;
import arc.util.Log;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Player;
import mindustry.mod.DataImagePacker;
import mindustry.mod.data.ImageAsset;
import mindustry.mod.data.PatchAsset;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprites used by the evolution menu.
 *
 * <p>Everything custom is streamed to the client with {@link mindustry.core.NetServer#sendTexture}
 * each time the menu opens, and referenced by its {@code net-} region:
 * <ul>
 *     <li><b>Connector lines</b> are bundled in the plugin jar, so they work no matter how the
 *     server's {@code config/assets} folder is set up.</li>
 *     <li><b>Custom unit icons</b> are read from the images the server loaded from
 *     {@code config/assets/sprites}. They can't be referenced as {@code dp-} regions directly:
 *     the v160 client keeps cached drawables of data images after the images are reloaded
 *     (DataImagePacker.unload clears its own atlas instead of {@code Core.atlas}), so after a
 *     rejoin or map change a {@code dp-} image in a server UI draws a disposed texture,
 *     i.e. nothing. Streamed textures are cleaned up properly.</li>
 * </ul>
 * The client drops streamed textures on every world load (map change, {@code /sync}), so they are
 * not cached per player; it swaps them into an already open menu as soon as they arrive.
 */
public final class EvolutionSprites {

    /** Client region of connector sprite {@code flags} is {@code LINK_REGION + flags}. */
    static final String LINK_REGION = DataImagePacker.serverRegionPrefix + "evo-link-";
    /** Built-in transparent region, shown for the moment before a streamed sprite arrives. */
    static final String LINK_PLACEHOLDER = "clear";
    /** Built-in icon shown when no image exists for a unit at all, instead of the "nomap" sprite. */
    static final String MISSING_ICON = "units";

    private static final String ICON_TEXTURE = "evo-icon-";
    /** streamed icons are shrunk to this size; the menu draws them at 40 UI units */
    private static final int ICON_TEXTURE_SIZE = 96;
    private static final String LINK_TEXTURE = "evo-link-";
    private static final String LINK_RESOURCE = "/evolution/links/";
    private static final int LINK_SPRITES = 31;

    /** Client region of a unit's menu icon, and a second region to try if the first is missing. */
    public record Icon(String region, String placeholder) {}

    /** Resolved icons, plus the PNGs to stream for the ones that come from data images. */
    private record Icons(Map<String, Icon> icons, Map<String, byte[]> textures) {}

    private static byte[][] links;
    /** rebuilt after every world load, since data assets are reloaded then */
    private static volatile Icons icons;

    private EvolutionSprites() {}

    static void init() {
        Events.on(EventType.WorldLoadEvent.class, e -> icons = null);
    }

    // ------------------------------------------------------------------ streaming

    /** Streams connector sprites and custom unit icons to the player. Call right before showing the menu. */
    static void sendTextures(Player player) {
        if (Vars.netServer == null || player.con == null) return;
        byte[][] data = links();
        for (int flags = 1; flags <= LINK_SPRITES; flags++) {
            if (data[flags] != null) Vars.netServer.sendTexture(player.con, LINK_TEXTURE + flags, data[flags]);
        }
        icons().textures.forEach((unit, png) -> Vars.netServer.sendTexture(player.con, ICON_TEXTURE + unit, png));
    }

    private static synchronized byte[][] links() {
        if (links != null) return links;
        links = new byte[LINK_SPRITES + 1][];
        for (int flags = 1; flags <= LINK_SPRITES; flags++) {
            String path = LINK_RESOURCE + LINK_TEXTURE + flags + ".png";
            try (InputStream in = EvolutionSprites.class.getResourceAsStream(path)) {
                if (in == null) {
                    Log.err("[EvolutionMenu] Missing resource @", path);
                    continue;
                }
                links[flags] = in.readAllBytes();
            } catch (IOException e) {
                Log.err("[EvolutionMenu] Could not read " + path, e);
            }
        }
        return links;
    }

    // ------------------------------------------------------------------ unit icons

    /** Menu icon of a unit. */
    static Icon icon(String unit) {
        Icon icon = icons().icons.get(unit);
        return icon != null ? icon : resolve(unit, null, null, Set.of());
    }

    private static Icons icons() {
        Icons current = icons;
        if (current == null) icons = current = resolveIcons();
        return current;
    }

    private static Icons resolveIcons() {
        Map<String, String> uiIcons = new HashMap<>(), fullIcons = new HashMap<>();
        readPatches(uiIcons, fullIcons);
        Map<String, ImageAsset> images = dataImages();

        Map<String, Icon> map = new HashMap<>();
        Map<String, byte[]> textures = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (Evolution evolution : Evolution.values()) {
            String unit = evolution.unitName;
            Icon icon = resolve(unit, uiIcons.get(unit), fullIcons.get(unit), images.keySet());
            ImageAsset image = images.get(icon.region);
            byte[] png = image != null ? iconTexture(image) : null;
            if (png != null) {
                // stream it; the data image only serves as placeholder until it arrives
                textures.put(unit, png);
                icon = new Icon(DataImagePacker.serverRegionPrefix + ICON_TEXTURE + unit, icon.region);
            }
            map.put(unit, icon);
            if (icon.placeholder.equals(MISSING_ICON) && isCustom(unit)) missing.add(unit);
        }
        if (!missing.isEmpty()) {
            Log.warn("[EvolutionMenu] No icon image in config/assets/sprites for: @", String.join(", ", missing));
        }
        return new Icons(map, textures);
    }

    /** PNG of a data image, shrunk to {@link #ICON_TEXTURE_SIZE}; null if it can't be read. */
    private static byte[] iconTexture(ImageAsset image) {
        try {
            var file = image.getCacheFile();
            if (file == null || !file.exists()) return null;
            byte[] png = file.readBytes();
            byte[] small = shrink(png, ICON_TEXTURE_SIZE);
            // re-encoded pixel art can be larger than the original; keep whichever is smaller
            return small != null && small.length < png.length ? small : png;
        } catch (Exception e) {
            Log.warn("[EvolutionMenu] Could not read @: @", image.path, e.getMessage());
            return null;
        }
    }

    /** Scales a PNG down to fit {@code max} pixels, averaging in premultiplied alpha (no dark fringes). */
    static byte[] shrink(byte[] png, int max) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        if (src == null) return null;
        int sw = src.getWidth(), sh = src.getHeight();
        if (Math.max(sw, sh) <= max) return png;

        double scale = (double) Math.max(sw, sh) / max;
        int dw = Math.max(1, (int) Math.round(sw / scale)), dh = Math.max(1, (int) Math.round(sh / scale));
        int[] pixels = src.getRGB(0, 0, sw, sh, null, 0, sw);
        BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB);

        for (int dy = 0; dy < dh; dy++) {
            double y0 = dy * scale, y1 = Math.min(sh, y0 + scale);
            for (int dx = 0; dx < dw; dx++) {
                double x0 = dx * scale, x1 = Math.min(sw, x0 + scale);
                double a = 0, r = 0, g = 0, b = 0, area = 0;
                for (int sy = (int) y0; sy < y1; sy++) {
                    double wy = Math.min(y1, sy + 1) - Math.max(y0, sy);
                    for (int sx = (int) x0; sx < x1; sx++) {
                        double w = wy * (Math.min(x1, sx + 1) - Math.max(x0, sx));
                        int c = pixels[sy * sw + sx];
                        double pa = (c >>> 24) / 255.0 * w;
                        a += pa;
                        r += ((c >> 16) & 0xff) * pa;
                        g += ((c >> 8) & 0xff) * pa;
                        b += (c & 0xff) * pa;
                        area += w;
                    }
                }
                int argb = 0;
                if (a > 0) {
                    argb = (int) Math.round(a / area * 255) << 24
                            | (int) Math.round(r / a) << 16
                            | (int) Math.round(g / a) << 8
                            | (int) Math.round(b / a);
                }
                dst.setRGB(dx, dy, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(dst, "png", out);
        return out.toByteArray();
    }

    /**
     * Picks the icon region for a unit.
     *
     * @param uiIcon   {@code uiIcon} set by a data patch, or null
     * @param fullIcon {@code fullIcon} set by a data patch, or null
     * @param regions  client region names of every data image the server loaded
     */
    static Icon resolve(String unit, String uiIcon, String fullIcon, Set<String> regions) {
        if (!isCustom(unit) && uiIcon == null) {
            // vanilla unit: the client atlas always has its icon
            return new Icon("unit-" + unit + "-ui", MISSING_ICON);
        }

        Set<String> found = new LinkedHashSet<>();
        addPatched(found, uiIcon, regions);
        String base = unit.startsWith(DataImagePacker.regionPrefix) ? unit.substring(DataImagePacker.regionPrefix.length()) : unit;
        List<String> bases = new ArrayList<>(List.of(base));
        if (base.endsWith("-unit")) bases.add(base.substring(0, base.length() - "-unit".length()));
        for (String suffix : new String[]{"-ui", "-full", ""}) {
            if (suffix.equals("-full")) addPatched(found, fullIcon, regions);
            for (String b : bases) {
                String region = DataImagePacker.regionPrefix + b + suffix;
                if (regions.contains(region)) found.add(region);
            }
            // icons the client generates itself for content-file units
            String generated = "unit-" + unit + suffix;
            if (!suffix.isEmpty() && regions.contains(generated)) found.add(generated);
        }

        List<String> list = new ArrayList<>(found);
        if (list.isEmpty()) return new Icon("unit-" + unit + "-ui", MISSING_ICON);
        return new Icon(list.get(0), list.size() > 1 ? list.get(1) : MISSING_ICON);
    }

    /** A patched icon counts if it is a vanilla region, or a data image that actually exists. */
    private static void addPatched(Set<String> found, String region, Set<String> regions) {
        if (region == null || region.isEmpty()) return;
        if (region.startsWith("icon-")) region = region.substring("icon-".length());
        if (!region.startsWith(DataImagePacker.regionPrefix) || regions.contains(region)) found.add(region);
    }

    private static boolean isCustom(String unit) {
        return unit.startsWith(DataImagePacker.regionPrefix);
    }

    /** All loaded data images by their client-side region name (see {@code DataImagePacker.pack}). */
    private static Map<String, ImageAsset> dataImages() {
        Map<String, ImageAsset> images = new HashMap<>();
        if (Vars.state == null || Vars.state.data == null) return images;
        for (ImageAsset image : Vars.state.data.getImages()) {
            images.put(image.isGenerated() && image.name.contains("-dp-") ? image.name : DataImagePacker.regionPrefix + image.name, image);
        }
        return images;
    }

    // ------------------------------------------------------------------ data patches

    private static void readPatches(Map<String, String> uiIcons, Map<String, String> fullIcons) {
        try {
            if (Vars.state == null || Vars.state.data == null) return;
            for (PatchAsset patch : Vars.state.data.getPatches()) {
                JsonValue json = patch.json;
                if (json == null || !json.isObject()) {
                    try {
                        json = new JsonReader().parse(Jval.read(patch.patch).toString(Jval.Jformat.plain));
                    } catch (Exception ignored) {
                        continue;
                    }
                }
                collectIcons(json, "uiIcon", uiIcons);
                collectIcons(json, "fullIcon", fullIcons);
            }
        } catch (Exception e) {
            Log.warn("[EvolutionMenu] Could not read unit icons from patches: @", e.getMessage());
        }
    }

    /** Collects {@code unit.<name>.<field>} values from one parsed patch, nested or dotted. */
    static void collectIcons(JsonValue patch, String field, Map<String, String> out) {
        collectIcons(patch, "", "." + field, out);
    }

    private static void collectIcons(JsonValue value, String path, String suffix, Map<String, String> out) {
        for (JsonValue child = value.child; child != null; child = child.next) {
            String key = child.name == null ? path : (path.isEmpty() ? child.name : path + "." + child.name);
            if (child.isObject() || child.isArray()) {
                collectIcons(child, key, suffix, out);
            } else if (child.isString() && key.startsWith("unit.") && key.endsWith(suffix)) {
                String unit = key.substring("unit.".length(), key.length() - suffix.length());
                if (!unit.isEmpty() && !unit.contains(".")) out.put(unit, child.asString());
            }
        }
    }
}
