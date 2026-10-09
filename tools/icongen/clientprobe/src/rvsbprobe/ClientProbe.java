package rvsbprobe;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.mod.*;
import mindustry.mod.data.*;
import mindustry.type.*;

import java.util.*;

/**
 * Verification probe for the icon generator (not part of the generator itself).
 *
 * Runs inside a real Mindustry desktop client: loads a server's data asset folder exactly like a client joining that
 * server does (asset list built like ServerControl.loadDataAssets, then DataManager.load), then draws every unit with
 * the client's real renderer (GL, packed + antialiased atlas, sorted sprite batch) into a framebuffer, in the same
 * pose the icon generator uses (payload pose: facing up, at rest), 1 sprite pixel = 1 framebuffer pixel. Soft shadow,
 * engines and trails are disabled, like in the generator.
 *
 * System properties: probe.assets (config/assets), probe.out (output dir), probe.units (comma list; default: all
 * data units), probe.vanilla (comma list of vanilla units to also render).
 */
public class ClientProbe extends Mod{
    public ClientProbe(){
        Events.on(ClientLoadEvent.class, e -> Core.app.post(() -> Core.app.post(this::run)));
    }

    void run(){
        Fi out = Fi.get(System.getProperty("probe.out", "probe-out"));
        out.mkdirs();
        try{
            Fi assetDir = Fi.get(System.getProperty("probe.assets"));
            Seq<DataAsset> assets = new Seq<>();
            String prefix = "server-assets/";
            for(var type : DataAssetType.all){
                Fi folder = assetDir.child(type.folder);
                if(!folder.exists()) continue;
                if(type == DataAssetType.content){
                    for(ContentType ctype : ContentAsset.loadableContent){
                        Fi subfolder = folder.child(ctype.folderName);
                        if(!subfolder.exists()) continue;
                        for(Fi file : subfolder.findAll(f -> type.extensions.contains(f.extension().toLowerCase(Locale.ROOT)))){
                            ContentAsset asset = (ContentAsset)type.create();
                            asset.readOverride(prefix + file.absolutePath().substring(subfolder.absolutePath().length() + 1), file, ctype);
                            assets.add(asset);
                        }
                    }
                }else{
                    for(Fi file : folder.findAll(f -> type.extensions.contains(f.extension().toLowerCase(Locale.ROOT)))){
                        var asset = type.create();
                        asset.readOverride(prefix + file.absolutePath().substring(folder.absolutePath().length() + 1), file);
                        assets.add(asset);
                    }
                }
            }
            assets.sort();
            Log.info("[probe] loading @ data assets", assets.size);
            Vars.state.data.load(assets);

            String unitsProp = System.getProperty("probe.units", "");
            Seq<UnitType> units = Vars.content.units().select(u -> u.name.startsWith("dp-"));
            if(!unitsProp.isEmpty()){
                ObjectSet<String> only = ObjectSet.with(unitsProp.split(","));
                units.retainAll(u -> only.contains(u.name) || only.contains(u.name.substring(3)));
            }
            for(String v : System.getProperty("probe.vanilla", "").split(",")){
                UnitType t = Vars.content.unit(v);
                if(t != null) units.add(t);
            }

            //unit drawing checks player.team() for fog
            Vars.player.team(Team.sharded);

            for(UnitType type : units){
                try{
                    render(type, out);
                }catch(Throwable t){
                    Log.err("[probe] " + type.name, t);
                }
            }
            out.child("done.txt").writeString("ok");
        }catch(Throwable t){
            Log.err("[probe] failed", t);
            out.child("done.txt").writeString("failed: " + Strings.getStackTrace(t));
        }
        Core.app.exit();
    }

    void render(UnitType type, Fi out){
        boolean oddX = type.region.width % 2 == 1, oddY = type.region.height % 2 == 1;
        int half = 700;
        int w = half * 2 + (oddX ? 1 : 0), h = half * 2 + (oddY ? 1 : 0);
        float scl = Draw.scl;

        Unit unit = type.create(Team.sharded);
        unit.set(0f, 0f);
        unit.rotation = 90f;
        if(unit instanceof Mechc mech) mech.baseRotation(90f);

        boolean softShadow = type.drawSoftShadow;
        var engines = type.engines;
        int trail = type.trailLength;
        type.drawSoftShadow = false;
        type.engines = new Seq<>();
        type.trailLength = 0;

        FrameBuffer buffer = new FrameBuffer(w, h);
        Mat proj = new Mat(Draw.proj()), trans = new Mat(Draw.trans());
        buffer.begin(Color.clear);
        Draw.proj(new Mat().setOrtho(-w / 2f * scl, -h / 2f * scl, w * scl, h * scl));
        Draw.trans(new Mat());
        Draw.sort(true);
        Draw.reset();
        Draw.z(Layer.groundUnit);
        type.draw(unit);
        Draw.reset();
        Draw.sort(false);
        Draw.flush();
        Pixmap pix = ScreenUtils.getFrameBufferPixmap(0, 0, w, h, true);
        buffer.end();
        buffer.dispose();
        Draw.proj(proj);
        Draw.trans(trans);

        type.drawSoftShadow = softShadow;
        type.engines = engines;
        type.trailLength = trail;

        //framebuffer holds premultiplied colour (blending onto transparent black); store straight alpha
        pix.each((x, y) -> {
            int c = pix.getRaw(x, y);
            int a = c & 0xff;
            if(a == 0){
                pix.setRaw(x, y, 0);
                return;
            }
            int r = Math.min(255, Math.round(((c >>> 24) & 0xff) * 255f / a));
            int g = Math.min(255, Math.round(((c >>> 16) & 0xff) * 255f / a));
            int b = Math.min(255, Math.round(((c >>> 8) & 0xff) * 255f / a));
            pix.setRaw(x, y, (r << 24) | (g << 16) | (b << 8) | a);
        });
        out.child(type.name + ".png").writePng(pix);
        pix.dispose();
        Log.info("[probe] rendered @ (@x@)", type.name, w, h);
    }
}
