package eu.vcmi.vcmi.thor;

import android.content.Context;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import eu.vcmi.vcmi.R;
import eu.vcmi.vcmi.Storage;


/**
 * First-run setup for the Steam "HD Edition" (Restoration of Erathia content only):
 * when its LODs are in vcmi-data/Data and not prepared yet, convert the tables, install the
 * roe-support mod (JSON from the APK + campaigns converted from the owner's data) and enable it.
 * Runs before the launcher / game read their configuration. Idempotent (marker file).
 */
public final class RoeSetup {
    private RoeSetup() {}

    private static final String MARKER = ".thor-roe-bridge";

    public static void runIfNeeded(Context ctx) {
        File root = Storage.getVcmiDataDir(ctx);
        File data = find(root, "Data");
        if (data == null) return;
        File base = find(data, "H3bitmap.lod");
        if (base == null || find(data, "H3ab_bmp.lod") != null) return; // no data, or AB/SoD present
        File marker = new File(data, MARKER);
        try {
            if (marker.exists() && new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim()
                    .equals(String.valueOf(RoeBridge.VERSION))) {
                // the launcher may rewrite modSettings.json without the mod: keep it enabled
                if (new File(new File(root, "Mods"), "roe-support").isDirectory())
                    enableMod(new File(new File(root, "config"), "modSettings.json"));
                return;
            }
            long t0 = System.currentTimeMillis();
            Map<String, byte[]> tables = RoeBridge.readLod(base, ".TXT", ".H3C");
            byte[] heroScreen = RoeBridge.readLod(base, "HEROSCR3.PCX").get("HEROSCR3.PCX");
            File loc = find(data, "H3pbitma.lod"); // localized tables (German HD Edition)
            if (loc != null) tables.putAll(RoeBridge.readLod(loc, ".TXT", ".H3C"));
            if (!isRoe(tables)) {
                android.util.Log.i("ThorHeroes", "RoE bridge: tables are not the RoE layout, nothing to do");
                return;
            }
            for (Map.Entry<String, byte[]> e : RoeBridge.convert(tables, loc != null).entrySet()) write(new File(data, e.getKey()), e.getValue());

            // VCMI's hero screen is the SoD background HeroScr4; RoE ships HEROSCR3
            if (heroScreen != null) write(new File(data, "HEROSCR4.PCX"), heroScreen);
            File mod = new File(new File(root, "Mods"), "roe-support");
            deleteTree(mod);
            unzip(ctx.getResources().openRawResource(R.raw.roe_support), mod);
            int campaigns = RoeBridge.campaigns(tables, new File(mod, "content/data"));
            copyTutorial(root);
            enableMod(new File(new File(root, "config"), "modSettings.json"));
            write(marker, String.valueOf(RoeBridge.VERSION).getBytes(StandardCharsets.UTF_8));
            android.util.Log.i("ThorHeroes", "RoE bridge: prepared in " + (System.currentTimeMillis() - t0) + " ms, campaigns " + campaigns);
            boolean de = Locale.getDefault().getLanguage().equals("de");
            String msg = de ? "Steam HD Edition (Restoration of Erathia) f\u00fcr VCMI eingerichtet"
                    : "Steam HD Edition (Restoration of Erathia) set up for VCMI";
            // may run on the launcher's Qt thread: toasts need the main looper
            new android.os.Handler(android.os.Looper.getMainLooper()).post(
                    () -> Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_LONG).show());
        } catch (Throwable t) {
            android.util.Log.e("ThorHeroes", "RoE bridge failed", t);
        }
    }

    /** RoE tables: 127 artifacts (SoD has 144). */
    private static boolean isRoe(Map<String, byte[]> tables) {
        byte[] art = tables.get("ARTRAITS.TXT");
        if (art == null) return false;
        int n = 0;
        for (java.util.List<String> r : RoeBridge.rows(RoeBridge.latin(art)))
            if (!r.isEmpty() && !r.get(0).trim().isEmpty()) n++;
        return n == 127 + 1; // header row with "Name" + 127 artifacts (SoD: 144)
    }

    private static File find(File dir, String name) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) if (f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }

    private static void write(File f, byte[] b) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b);
        }
    }

    private static void unzip(InputStream in, File dst) throws IOException {
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(dst, e.getName());
                if (!f.getCanonicalPath().startsWith(dst.getCanonicalPath() + File.separator)) continue; // zip slip
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** VCMI looks for Maps/Tutorial.tut; the HD Edition keeps it in a language folder. */
    private static void copyTutorial(File root) throws IOException {
        File maps = find(root, "Maps");
        if (maps == null || find(maps, "Tutorial.tut") != null) return;
        File[] dirs = maps.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File d : dirs) {
            File t = find(d, "Tutorial.tut");
            if (t != null) {
                write(new File(maps, "Tutorial.tut"), Files.readAllBytes(t.toPath()));
                return;
            }
        }
    }

    private static void enableMod(File settings) throws Exception {
        JSONObject o = settings.exists()
                ? new JSONObject(new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8))
                : new JSONObject("{\"activePreset\":\"default\",\"presets\":{\"default\":{\"mods\":[\"vcmi\",\"core\"]}}}");
        String preset = o.optString("activePreset", "default");
        JSONObject presets = o.optJSONObject("presets");
        if (presets == null) { presets = new JSONObject(); o.put("presets", presets); }
        JSONObject p = presets.optJSONObject(preset);
        if (p == null) { p = new JSONObject(); presets.put(preset, p); }
        JSONArray mods = p.optJSONArray("mods");
        if (mods == null) { mods = new JSONArray(); p.put("mods", mods); }
        for (int i = 0; i < mods.length(); i++) if ("roe-support".equals(mods.optString(i))) return;
        mods.put("roe-support");
        write(settings, o.toString(1).getBytes(StandardCharsets.UTF_8));
    }
}
