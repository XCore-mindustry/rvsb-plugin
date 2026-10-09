package rvsb.icongen;

import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData.*;
import arc.struct.*;
import arc.util.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * The packed sprite atlas of a Mindustry client jar ({@code sprites/sprites.aatls} + pages).
 *
 * This is what a real client has in {@code Core.atlas} before any data patch is loaded, so it is the ground truth
 * for "does this sprite exist in game", and its pixels (already antialiased by the vanilla pack step) are the ground
 * truth for what each sprite looks like in game.
 */
public class JarAtlas{
    final ObjectMap<String, Region> regions = new ObjectMap<>();
    final ObjectMap<AtlasPage, Pixmap> pages = new ObjectMap<>();
    final TextureAtlasData data;
    final Fi tmp;

    public JarAtlas(Fi jar) throws IOException{
        tmp = Fi.tempDirectory("icongen-atlas");
        try(ZipFile zip = new ZipFile(jar.file())){
            ZipEntry atlasEntry = zip.getEntry("sprites/sprites.aatls");
            if(atlasEntry == null) throw new IOException(jar + " has no sprites/sprites.aatls; pass a Mindustry *client* jar.");
            for(ZipEntry entry : Collections.list(zip.entries())){
                String n = entry.getName();
                if(n.startsWith("sprites/") && n.indexOf('/', 8) == -1 && (n.endsWith(".png") || n.endsWith(".aatls"))){
                    try(InputStream in = zip.getInputStream(entry)){
                        tmp.child(n.substring(8)).write(in, false);
                    }
                }
            }
            data = new TextureAtlasData(tmp.child("sprites.aatls"), tmp, false);
        }
        for(Region r : data.getRegions()) regions.put(r.name, r);
    }

    public boolean has(String name){
        return regions.containsKey(name);
    }

    public ObjectMap.Keys<String> names(){
        return regions.keys();
    }

    /** @return the region at its original (untrimmed) size, or null. The atlas strips transparent borders symmetrically. */
    public @Nullable Pixmap crop(String name){
        Region r = regions.get(name);
        if(r == null) return null;
        Pixmap page = pages.get(r.page);
        if(page == null){
            page = new Pixmap(r.page.textureFile);
            pages.put(r.page, page);
        }
        int ow = r.originalWidth == 0 ? r.width : r.originalWidth, oh = r.originalHeight == 0 ? r.height : r.originalHeight;
        int ox = (int)r.offsetX, oy = oh - r.height - (int)r.offsetY;
        Pixmap pix = new Pixmap(ow, oh);
        for(int y = 0; y < r.height; y++){
            for(int x = 0; x < r.width; x++){
                pix.setRaw(ox + x, oy + y, page.getRaw(r.left + x, r.top + y));
            }
        }
        return pix;
    }

    /** Frees the page pixmaps (they are re-read on demand). */
    public void releasePages(){
        pages.each((p, pix) -> pix.dispose());
        pages.clear();
    }

    public void dispose(){
        releasePages();
        tmp.deleteDirectory();
    }
}
