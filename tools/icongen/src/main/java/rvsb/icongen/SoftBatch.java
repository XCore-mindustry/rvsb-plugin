package rvsb.icongen;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.icongen.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * A software implementation of the game's sprite batch (arc {@code SpriteBatch} in sorted mode, v160.7 / Arc 7445105cd2),
 * so that the game's real drawing code can render a unit into a pixmap without a GPU.
 *
 * Reproduced exactly: vertex construction (same math as SpriteBatch.constructVertices), draw order (requests are
 * stably sorted by {@code floatBits(z + 16)} - SpriteBatch uses a counting sort on the same key), the default
 * fragment shader {@code v_color * mix(tex, vec4(v_mix_color.rgb, tex.a), v_mix_color.a)} including the 255/254
 * alpha correction, and the GL blend functions of {@link Blending#normal} / {@link Blending#additive}.
 *
 * Approximated: texture filtering is bilinear with clamped edges and alpha-weighted colour (equivalent to GL linear
 * filtering on a bleeding-corrected atlas). For sprites drawn 1:1 on the pixel grid - nearly everything - this is
 * identical to nearest sampling, i.e. exact. The framebuffer is float instead of 8 bit.
 *
 * Coordinates: 1 sprite pixel = 1 output pixel ({@code Draw.scl} world units). The output pixmap has its centre at the
 * world origin, and is cropped symmetrically to the drawn pixels (so it can be drawn centred on the unit, like
 * vanilla's full icon / shadow).
 */
public class SoftBatch extends Batch{
    static final IdentityHashMap<Texture, GenRegion> textures = new IdentityHashMap<>();
    static final Object unsafe;
    static final Method allocate;

    static{
        try{
            Class<?> c = Class.forName("sun.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
            allocate = c.getMethod("allocateInstance", Class.class);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    /** A Texture object that has no GL state (never bound); identifies the region's pixels in vertex draws. */
    static Texture textureFor(GenRegion region){
        try{
            Texture tex = (Texture)allocate.invoke(unsafe, Texture.class);
            tex.width = region.width;
            tex.height = region.height;
            textures.put(tex, region);
            return tex;
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    static class Request{
        final float[] verts;
        final GenRegion region;
        final Blending blending;
        final int key, index;

        Request(float[] verts, GenRegion region, Blending blending, int key, int index){
            this.verts = verts;
            this.region = region;
            this.blending = blending;
            this.key = key;
            this.index = index;
        }
    }

    final Seq<Request> requests = new Seq<>();
    /** Regions that are never part of an icon (shadows). */
    final ObjectSet<String> skipped = new ObjectSet<>();
    /** Regions drawn as the error sprite. */
    final OrderedSet<String> errors = new OrderedSet<>();
    /** ...of which these are visible in the result (not completely covered by other sprites). */
    final OrderedSet<String> visibleErrors = new OrderedSet<>();
    final OrderedSet<String> unknownTextures = new OrderedSet<>();
    /** Draws dropped because they lie entirely outside the clip square (region name -> example position). */
    final OrderedMap<String, String> clipped = new OrderedMap<>();

    @Override
    protected void z(float z){
        this.z = z;
    }

    @Override
    protected void setBlending(Blending blending){
        this.blending = blending;
    }

    @Override
    protected void setShader(arc.graphics.gl.Shader shader, boolean apply){
        //custom shaders are not used by unit drawing at rest
        customShader = shader;
    }

    @Override
    protected void flush(){}

    @Override
    protected void draw(Texture texture, float[] spriteVertices, int offset, int count){
        GenRegion region = textures.get(texture);
        if(region == null){
            unknownTextures.add(String.valueOf(texture));
            return;
        }
        for(int i = 0; i + 24 <= count; i += 24){
            add(Arrays.copyOfRange(spriteVertices, offset + i, offset + i + 24), region);
        }
    }

    @Override
    protected void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float rotation){
        if(!(region instanceof GenRegion g) || g.pixmap == null){
            unknownTextures.add(String.valueOf(region));
            return;
        }
        float[] v = new float[24];
        constructVertices(v, region, x, y, originX, originY, width, height, rotation);
        add(v, g);
    }

    void add(float[] verts, GenRegion region){
        if(skipped.contains(region.name)) return;
        if(region.invalid){
            errors.add(region.name);
            GenAtlas.drawnErrors.add(region.name);
        }
        requests.add(new Request(verts, region, blending, Float.floatToRawIntBits(z + 16f), requests.size));
        if(System.getenv("ICONGEN_DEBUG") != null){
            Log.info("  draw @ z=@ v0=(@, @) v2=(@, @) color=@ mix=@", region.name, z, verts[0], verts[1], verts[12], verts[13],
                Integer.toHexString(Float.floatToRawIntBits(verts[2])), Integer.toHexString(Float.floatToRawIntBits(verts[5])));
        }
    }

    /** Copy of SpriteBatch.constructVertices (Arc 7445105cd2). */
    void constructVertices(float[] vertices, TextureRegion region, float x, float y, float originX, float originY, float width, float height, float rotation){
        float u = region.u;
        float v = region.v2;
        float u2 = region.u2;
        float v2 = region.v;

        float color = this.colorPacked;
        float mixColor = this.mixColorPacked;
        int idx = 0;

        if(!Mathf.zero(rotation)){
            float worldOriginX = x + originX;
            float worldOriginY = y + originY;
            float fx = -originX;
            float fy = -originY;
            float fx2 = width - originX;
            float fy2 = height - originY;

            float cos = Mathf.cosDeg(rotation);
            float sin = Mathf.sinDeg(rotation);

            float x1 = cos * fx - sin * fy + worldOriginX;
            float y1 = sin * fx + cos * fy + worldOriginY;
            float x2 = cos * fx - sin * fy2 + worldOriginX;
            float y2 = sin * fx + cos * fy2 + worldOriginY;
            float x3 = cos * fx2 - sin * fy2 + worldOriginX;
            float y3 = sin * fx2 + cos * fy2 + worldOriginY;
            float x4 = x1 + (x3 - x2);
            float y4 = y3 - (y2 - y1);

            put(vertices, 0, x1, y1, color, u, v, mixColor);
            put(vertices, 6, x2, y2, color, u, v2, mixColor);
            put(vertices, 12, x3, y3, color, u2, v2, mixColor);
            put(vertices, 18, x4, y4, color, u2, v, mixColor);
        }else{
            float fx2 = x + width;
            float fy2 = y + height;

            put(vertices, 0, x, y, color, u, v, mixColor);
            put(vertices, 6, x, fy2, color, u, v2, mixColor);
            put(vertices, 12, fx2, fy2, color, u2, v2, mixColor);
            put(vertices, 18, fx2, y, color, u2, v, mixColor);
        }
    }

    static void put(float[] v, int i, float x, float y, float color, float u, float vv, float mix){
        v[i] = x; v[i + 1] = y; v[i + 2] = color; v[i + 3] = u; v[i + 4] = vv; v[i + 5] = mix;
    }

    // ---- rasterization ----

    /**
     * Rasterizes everything that was drawn.
     * @param scl world units per output pixel (Draw.scl)
     * @param oddX whether the output width must be odd (the world origin lies on a pixel centre), else even
     * @param oddY same for the height
     * @return the image, or null if nothing visible was drawn
     */
    public @Nullable Pixmap render(float scl, boolean oddX, boolean oddY, float clipSize){
        //like the game's entity culling (a clipSize x clipSize square around the unit): anything entirely outside it is
        //not drawn whenever the unit itself is off screen, i.e. it is not part of the unit. Data units use this to hide
        //parts ("y: 2000").
        float clip = clipSize / 2f;
        requests.removeAll(r -> {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for(int i = 0; i < 24; i += 6){
                minX = Math.min(minX, r.verts[i]); maxX = Math.max(maxX, r.verts[i]);
                minY = Math.min(minY, r.verts[i + 1]); maxY = Math.max(maxY, r.verts[i + 1]);
            }
            boolean out = maxX < -clip || minX > clip || maxY < -clip || minY > clip;
            if(out && !clipped.containsKey(r.region.name)){
                clipped.put(r.region.name, Strings.autoFixed((minX + maxX) / 2f, 1) + ", " + Strings.autoFixed((minY + maxY) / 2f, 1));
            }
            return out;
        });
        if(requests.isEmpty()) return null;

        //stable sort by key, like SpriteBatch's counting sort
        Request[] sorted = requests.toArray(Request.class);
        Arrays.sort(sorted, (a, b) -> a.key != b.key ? Integer.compare(a.key, b.key) : Integer.compare(a.index, b.index));

        float maxX = 0f, maxY = 0f;
        for(Request r : sorted){
            for(int i = 0; i < 24; i += 6){
                maxX = Math.max(maxX, Math.abs(r.verts[i]));
                maxY = Math.max(maxY, Math.abs(r.verts[i + 1]));
            }
        }
        //half sizes in pixels; +1 margin for filtering
        int hw = (int)Math.ceil(maxX / scl) + 2, hh = (int)Math.ceil(maxY / scl) + 2;
        int w = hw * 2 + (oddX ? 1 : 0), h = hh * 2 + (oddY ? 1 : 0);
        if((long)w * h > 4096L * 4096L) throw new IllegalArgumentException("Unit drawing is too large: " + w + "x" + h);

        float[] fb = rasterize(sorted, w, h, scl, null);
        //missing regions are drawn as the error sprite; find out which of them can actually be seen
        for(String name : errors){
            if(!Arrays.equals(fb, rasterize(sorted, w, h, scl, name))) visibleErrors.add(name);
        }

        //crop symmetrically around the centre to the visible pixels (keeps the width/height parity)
        float centerX = w / 2f, centerY = h / 2f, ex = 0f, ey = 0f;
        boolean any = false;
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                if(Math.round(Mathf.clamp(fb[(y * w + x) * 4 + 3]) * 255f) > 0){
                    any = true;
                    ex = Math.max(ex, Math.max(Math.abs(x - centerX), Math.abs(x + 1 - centerX)));
                    ey = Math.max(ey, Math.max(Math.abs(y - centerY), Math.abs(y + 1 - centerY)));
                }
            }
        }
        if(!any) return null;
        int ow = Math.round(ex * 2f), oh = Math.round(ey * 2f);
        int x0 = (w - ow) / 2, y0 = (h - oh) / 2;

        Pixmap out = new Pixmap(ow, oh);
        for(int y = 0; y < oh; y++){
            for(int x = 0; x < ow; x++){
                int i = ((y + y0) * w + (x + x0)) * 4;
                float a = Mathf.clamp(fb[i + 3]);
                int ai = Math.round(a * 255f);
                if(ai == 0){
                    out.setRaw(x, y, 0);
                    continue;
                }
                int ri = Math.round(Mathf.clamp(fb[i] / a) * 255f), gi = Math.round(Mathf.clamp(fb[i + 1] / a) * 255f), bi = Math.round(Mathf.clamp(fb[i + 2] / a) * 255f);
                out.setRaw(x, y, (ri << 24) | (gi << 16) | (bi << 8) | ai);
            }
        }
        return out;
    }

    static float[] rasterize(Request[] sorted, int w, int h, float scl, @Nullable String without){
        //premultiplied RGBA framebuffer, starts transparent
        float[] fb = new float[w * h * 4];
        //world coordinate of the left / top edge of the image
        float left = -w / 2f * scl, top = h / 2f * scl;

        for(Request r : sorted){
            if(without != null && r.region.name.equals(without)) continue;
            raster(fb, w, h, left, top, scl, r, 0, 1, 2);
            raster(fb, w, h, left, top, scl, r, 2, 3, 0);
        }
        return fb;
    }

    /** Rasterizes one triangle of a request (GL-style: pixel centres, top-left fill rule, barycentric interpolation). */
    static void raster(float[] fb, int w, int h, float left, float top, float scl, Request r, int i0, int i1, int i2){
        float[] v = r.verts;
        //vertex positions in pixel space (x right, y down)
        double x0 = (v[i0 * 6] - left) / scl, y0 = (top - v[i0 * 6 + 1]) / scl;
        double x1 = (v[i1 * 6] - left) / scl, y1 = (top - v[i1 * 6 + 1]) / scl;
        double x2 = (v[i2 * 6] - left) / scl, y2 = (top - v[i2 * 6 + 1]) / scl;

        double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if(Math.abs(area) < 1e-12) return;
        if(area < 0){
            //make the winding consistent (mirrored sprites have negative width)
            double tx = x1, ty = y1; x1 = x2; y1 = y2; x2 = tx; y2 = ty;
            int ti = i1; i1 = i2; i2 = ti;
            area = -area;
        }

        int minX = Math.max(0, (int)Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(w - 1, (int)Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int)Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(h - 1, (int)Math.ceil(Math.max(y0, Math.max(y1, y2))));

        GenRegion region = r.region;
        Pixmap tex = region.pixmap;
        int tw = tex.width, th = tex.height;

        float[][] col = {decode(v[i0 * 6 + 2]), decode(v[i1 * 6 + 2]), decode(v[i2 * 6 + 2])};
        float[][] mix = {decode(v[i0 * 6 + 5]), decode(v[i1 * 6 + 5]), decode(v[i2 * 6 + 5])};
        double[] us = {v[i0 * 6 + 3], v[i1 * 6 + 3], v[i2 * 6 + 3]}, vs = {v[i0 * 6 + 4], v[i1 * 6 + 4], v[i2 * 6 + 4]};
        boolean additive = r.blending == Blending.additive;
        float[] texel = new float[4];

        for(int py = minY; py <= maxY; py++){
            double cy = py + 0.5;
            for(int px = minX; px <= maxX; px++){
                double cx = px + 0.5;
                double w0 = edge(x1, y1, x2, y2, cx, cy), w1 = edge(x2, y2, x0, y0, cx, cy), w2 = edge(x0, y0, x1, y1, cx, cy);
                if(!inside(w0, x1, y1, x2, y2) || !inside(w1, x2, y2, x0, y0) || !inside(w2, x0, y0, x1, y1)) continue;
                w0 /= area; w1 /= area; w2 /= area;

                double u = us[0] * w0 + us[1] * w1 + us[2] * w2, vv = vs[0] * w0 + vs[1] * w1 + vs[2] * w2;
                sample(tex, tw, th, u, vv, texel);

                float ca = (float)(col[0][3] * w0 + col[1][3] * w1 + col[2][3] * w2);
                float cr, cg, cb;
                if(region.cell){
                    //icon cell palette instead of the team-coloured tint (alpha still applies)
                    cr = cg = cb = 1f;
                }else{
                    cr = (float)(col[0][0] * w0 + col[1][0] * w1 + col[2][0] * w2);
                    cg = (float)(col[0][1] * w0 + col[1][1] * w1 + col[2][1] * w2);
                    cb = (float)(col[0][2] * w0 + col[1][2] * w1 + col[2][2] * w2);
                }
                float mr = (float)(mix[0][0] * w0 + mix[1][0] * w1 + mix[2][0] * w2);
                float mg = (float)(mix[0][1] * w0 + mix[1][1] * w1 + mix[2][1] * w2);
                float mb = (float)(mix[0][2] * w0 + mix[1][2] * w1 + mix[2][2] * w2);
                float ma = (float)(mix[0][3] * w0 + mix[1][3] * w1 + mix[2][3] * w2);

                //gl_FragColor = v_color * mix(c, vec4(v_mix_color.rgb, c.a), v_mix_color.a)
                float sr = cr * (texel[0] + (mr - texel[0]) * ma);
                float sg = cg * (texel[1] + (mg - texel[1]) * ma);
                float sb = cb * (texel[2] + (mb - texel[2]) * ma);
                float sa = Mathf.clamp(ca * texel[3]);
                if(sa <= 0f) continue;

                int i = (py * w + px) * 4;
                if(additive){
                    //srcAlpha, one | one, oneMinusSrcAlpha
                    fb[i] += sr * sa;
                    fb[i + 1] += sg * sa;
                    fb[i + 2] += sb * sa;
                }else{
                    //srcAlpha, oneMinusSrcAlpha | one, oneMinusSrcAlpha
                    fb[i] = sr * sa + fb[i] * (1f - sa);
                    fb[i + 1] = sg * sa + fb[i + 1] * (1f - sa);
                    fb[i + 2] = sb * sa + fb[i + 2] * (1f - sa);
                }
                fb[i + 3] = sa + fb[i + 3] * (1f - sa);
                //the framebuffer stores 8 bits per channel after every blend
                for(int k = 0; k < 4; k++) fb[i + k] = Math.round(Mathf.clamp(fb[i + k]) * 255f) / 255f;
            }
        }
    }

    static double edge(double ax, double ay, double bx, double by, double px, double py){
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /** Top-left fill rule (pixel space, y down, positive winding), so shared edges are covered exactly once. */
    static boolean inside(double e, double ax, double ay, double bx, double by){
        if(e > 1e-9) return true;
        if(e < -1e-9) return false;
        double dx = bx - ax, dy = by - ay;
        //top edge: horizontal, going right (for this winding); left edge: going up
        return (Math.abs(dy) < 1e-12 && dx < 0) || dy > 0;
    }

    /** Bilinear sample with clamp-to-edge, colour weighted by alpha (like linear filtering on a bleeding-fixed atlas). */
    static void sample(Pixmap tex, int tw, int th, double u, double v, float[] out){
        double tx = u * tw - 0.5, ty = v * th - 0.5;
        int x0 = (int)Math.floor(tx), y0 = (int)Math.floor(ty);
        double fx = tx - x0, fy = ty - y0;
        //snap tiny float noise so that grid-aligned draws are exact
        if(fx < 1e-4){ fx = 0; }else if(fx > 1 - 1e-4){ fx = 0; x0++; }
        if(fy < 1e-4){ fy = 0; }else if(fy > 1 - 1e-4){ fy = 0; y0++; }

        float r = 0, g = 0, b = 0, a = 0;
        for(int j = 0; j < 2; j++){
            double wy = j == 0 ? 1 - fy : fy;
            if(wy == 0) continue;
            int sy = Mathf.clamp(y0 + j, 0, th - 1);
            for(int i = 0; i < 2; i++){
                double wx = i == 0 ? 1 - fx : fx;
                if(wx == 0) continue;
                int sx = Mathf.clamp(x0 + i, 0, tw - 1);
                int c = tex.getRaw(sx, sy);
                float ca = (c & 0xff) / 255f;
                float wgt = (float)(wx * wy);
                float wa = wgt * ca;
                r += ((c >>> 24) & 0xff) / 255f * wa;
                g += ((c >>> 16) & 0xff) / 255f * wa;
                b += ((c >>> 8) & 0xff) / 255f * wa;
                a += wa;
            }
        }
        if(a > 0){
            out[0] = r / a; out[1] = g / a; out[2] = b / a;
        }else{
            out[0] = out[1] = out[2] = 0;
        }
        out[3] = a;
    }

    /** Packed vertex colour (ABGR, alpha stored as max 254) to rgba floats, incl. the shader's 255/254 correction. */
    static float[] decode(float packed){
        int c = Float.floatToRawIntBits(packed);
        return new float[]{
            (c & 0xff) / 255f,
            ((c >>> 8) & 0xff) / 255f,
            ((c >>> 16) & 0xff) / 255f,
            Math.min(1f, ((c >>> 24) & 0xff) / 255f * (255f / 254f))
        };
    }
}
