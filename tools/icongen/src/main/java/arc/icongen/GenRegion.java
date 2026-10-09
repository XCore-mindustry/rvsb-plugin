package arc.icongen;

import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.TextureAtlas.*;

/**
 * ImagePacker's GenRegion (an atlas region backed by a raw sprite file).
 *
 * It lives in an {@code arc.*} package on purpose: ContentParser.checkNullFields skips every class whose name
 * starts with "arc.", which is why the client's real AtlasRegions (no CPU-side data, but a GPU texture) pass the
 * parser's null checks. Using this package makes data-patch parsing behave exactly like on a real client.
 *
 * In "in-game atlas" mode (see rvsb.icongen.IngameAtlas) a region also carries the pixels the game has for it
 * ({@link #pixmap}) and a GL-less stand-in {@link #texture} so that vertex-based drawing (Fill, Lines) can be mapped
 * back to pixels by the software batch.
 */
public class GenRegion extends AtlasRegion{
    public boolean invalid;
    public Fi path;
    /** In-game pixels (pre-antialias). For invalid regions in in-game mode: the "error" sprite, like Core.atlas.find. */
    public Pixmap pixmap;
    /** Draw with the vanilla icon cell palette instead of the (team-coloured) tint. */
    public boolean cell;

    public GenRegion(String name, Fi path){
        if(name == null) throw new IllegalArgumentException("name is null");
        this.name = name;
        this.path = path;
    }

    @Override
    public boolean found(){
        return !invalid;
    }
}
