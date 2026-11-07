package eu.vcmi.vcmi.thor;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thor Edition: copy plan for a Steam "Heroes III HD Edition" folder (contains "HOMM3 2.0.exe").
 * Picks only what VCMI + the RoE bridge need, in VCMI's layout; skips the HD .pak archives:
 * data/H3BITMAP.LOD, H3SPRITE.LOD, HEROES3.SND, maps, mp3, VIDEO and the localized
 * data/LOC/&lt;lang&gt;/h3bitmap.lod (-> H3pbitma.lod), heroes3cd.snd (-> Heroes3-cd2.snd), *.ogv.
 * Lines: "source-uri \t target-dir \t file-name" (same format as FileUtil.findFilesForCopy).
 */
public final class HdImport {
    private HdImport() {}

    /** null when root is not an HD Edition folder. */
    public static String[] list(DocumentFile root) {
        DocumentFile data = child(root, "data");
        if (child(root, "HOMM3 2.0.exe") == null || data == null || !data.isDirectory()) return null;
        List<String> out = new ArrayList<>();
        add(out, child(data, "H3BITMAP.LOD"), "Data", "H3bitmap.lod");
        add(out, child(data, "H3SPRITE.LOD"), "Data", "H3sprite.lod");
        add(out, child(data, "HEROES3.SND"), "Data", "Heroes3.snd");
        addDir(out, child(data, "maps"), "Maps", ".h3m", ".tut");
        addDir(out, child(data, "mp3"), "Mp3", ".mp3");
        addDir(out, child(data, "VIDEO"), "Video", ".ogv");
        DocumentFile lang = language(child(data, "LOC"));
        if (lang != null) {
            add(out, child(lang, "h3bitmap.lod"), "Data", "H3pbitma.lod");
            add(out, child(lang, "heroes3cd.snd"), "Data", "Heroes3-cd2.snd");
            addDir(out, lang, "Video", ".ogv");
            // localized maps (data/maps/<LANG>) come last so they replace the English ones
            addDir(out, child(child(data, "maps"), lang.getName()), "Maps", ".h3m", ".tut");
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    /** data/LOC/<lang>: the device language if present, otherwise the first one. */
    private static DocumentFile language(DocumentFile loc) {
        if (loc == null) return null;
        DocumentFile[] langs = loc.listFiles();
        DocumentFile first = null;
        for (DocumentFile l : langs) {
            if (!l.isDirectory()) continue;
            if (first == null) first = l;
            if (l.getName() != null && l.getName().equalsIgnoreCase(Locale.getDefault().getLanguage())) return l;
        }
        return first;
    }

    private static DocumentFile child(DocumentFile dir, String name) {
        if (dir == null || !dir.isDirectory()) return null;
        for (DocumentFile f : dir.listFiles()) {
            if (f.getName() != null && f.getName().equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    private static void add(List<String> out, DocumentFile f, String target, String name) {
        if (f != null && f.isFile()) out.add(f.getUri() + "\t" + target + "\t" + name);
    }

    /** files directly in dir (no recursion) with one of the extensions. */
    private static void addDir(List<String> out, DocumentFile dir, String target, String... exts) {
        if (dir == null || !dir.isDirectory()) return;
        for (DocumentFile f : dir.listFiles()) {
            String n = f.getName();
            if (n == null || !f.isFile()) continue;
            for (String e : exts) {
                if (n.toLowerCase(Locale.ROOT).endsWith(e)) {
                    add(out, f, target, n);
                    break;
                }
            }
        }
    }
}
