package eu.vcmi.vcmi.thor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * RoE bridge (Java port of thor/roe-bridge/convert.py + campaigns.py, no Android dependencies).
 *
 * The Steam "HD Edition" ships only Restoration of Erathia content; VCMI expects the Shadow of
 * Death table layout. This rewrites the owner's own text tables into that layout (missing SoD
 * entries become neutral placeholder rows that the roe-support mod hides) and repacks the RoE 1.0
 * campaigns (H3C version 1) as VCMI campaigns (.vcmp). No game data is shipped with the app.
 *
 * Text is handled as ISO-8859-1 strings so every byte maps 1:1 (tables are CP1252 on disk).
 */
public final class RoeBridge {
    private RoeBridge() {}

    public static final int VERSION = 9; // bump when the conversion output changes

    static final String PH = "(fehlt)";
    static final int SOD_CREATURES = 150, SOD_ARTIFACTS = 144, SOD_HEROES = 156, SOD_CLASSES = 18;
    static final int ROE_GENRLTXT = 709, SOD_GENRLTXT = 764;
    static final int ROE_BALLISTA = 119, SOD_ARROW_TOWER = 149;

    // ------------------------------------------------------------------ LOD

    /** LOD archive: name (upper case) -> file bytes, only for the given extensions. */
    public static Map<String, byte[]> readLod(File lod, String... exts) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (RandomAccessFile f = new RandomAccessFile(lod, "r")) {
            byte[] head = new byte[12];
            f.readFully(head);
            int n = le(head, 8);
            byte[] dir = new byte[n * 32];
            f.seek(92);
            f.readFully(dir);
            for (int i = 0; i < n; i++) {
                int e = i * 32;
                int z = 0;
                while (z < 16 && dir[e + z] != 0) z++;
                String name = new String(dir, e, z, StandardCharsets.ISO_8859_1).toUpperCase(java.util.Locale.ROOT);
                boolean want = false;
                for (String x : exts) want |= name.endsWith(x);
                if (!want) continue;
                int off = le(dir, e + 16), size = le(dir, e + 20), csize = le(dir, e + 28);
                byte[] raw = new byte[csize != 0 ? csize : size];
                f.seek(off & 0xFFFFFFFFL);
                f.readFully(raw);
                out.put(name, csize != 0 ? inflate(raw, size) : raw);
            }
        }
        return out;
    }

    private static int le(byte[] b, int o) {
        return ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static byte[] inflate(byte[] in, int size) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(in);
        byte[] out = new byte[size];
        try {
            int n = 0;
            while (n < size && !inf.finished()) n += inf.inflate(out, n, size - n);
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
        return out;
    }

    // ------------------------------------------------------------------ tables

    static String latin(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    /** Split like the original game: records end at CRLF outside quotes; a bare LF is text. */
    static List<List<String>> rows(String b) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        int i = 0, n = b.length();
        while (i < n) {
            int j;
            if (b.charAt(i) == '"') {
                j = i + 1;
                while (j < n) {
                    if (b.charAt(j) == '"') {
                        if (j + 1 < n && b.charAt(j + 1) == '"') { j += 2; continue; }
                        break;
                    }
                    j++;
                }
                j++;
                while (j < n && b.charAt(j) != '\t' && !b.startsWith("\r\n", j)) j++;
            } else {
                j = i;
                while (j < n && b.charAt(j) != '\t' && !b.startsWith("\r\n", j)) j++;
            }
            row.add(b.substring(i, Math.min(j, n)));
            if (b.startsWith("\r\n", j)) {
                out.add(row);
                row = new ArrayList<>();
                i = j + 2;
            } else {
                i = j + 1;
            }
        }
        if (!row.isEmpty()) out.add(row);
        return out;
    }

    static String dump(List<List<String>> rws) {
        StringBuilder sb = new StringBuilder();
        for (List<String> r : rws) {
            for (int k = 0; k < r.size(); k++) {
                if (k > 0) sb.append('\t');
                String f = r.get(k);
                sb.append(f.indexOf('\n') >= 0 && !f.startsWith("\"") ? "\"" + f.replace("\"", "\"\"") + "\"" : f);
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static List<String> renamed(List<String> row, String... names) {
        List<String> r = new ArrayList<>(row);
        for (int i = 0; i < names.length; i++) r.set(i, names[i]);
        return r;
    }

    private static List<List<String>> insertCol(List<List<String>> t, int idx, String header, String value) {
        List<List<String>> out = new ArrayList<>();
        for (int k = 0; k < t.size(); k++) {
            List<String> r = t.get(k);
            if (r.size() < idx) { out.add(r); continue; }
            List<String> nr = new ArrayList<>(r.subList(0, idx));
            nr.add(k == 1 ? header : k < 1 ? "" : value);
            nr.addAll(r.subList(idx, r.size()));
            out.add(nr);
        }
        return out;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("unexpected table layout: " + what);
    }

    private interface RowFn { List<String> f(List<String> r); }

    private static List<List<String>> creaturesLayout(List<List<String>> t, RowFn placeholder, RowFn arrowTower) {
        List<List<String>> recs = new ArrayList<>();
        for (List<String> r : t.subList(2, t.size())) if (!r.isEmpty() && !r.get(0).trim().isEmpty()) recs.add(r);
        check(recs.size() == 122, "creatures " + recs.size());
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < SOD_CREATURES; i++) out.add(null);
        for (int i = 0; i < recs.size(); i++) out.set(i >= 118 ? i + 27 : i, recs.get(i)); // war machines 118..121 -> 145..148
        out.set(SOD_ARROW_TOWER, arrowTower.f(recs.get(ROE_BALLISTA)));
        for (int i = 0; i < out.size(); i++) if (out.get(i) == null) out.set(i, placeholder.f(recs.get(0)));
        List<List<String>> res = new ArrayList<>(t.subList(0, 2));
        res.addAll(out);
        return res;
    }

    private static final Map<Integer, String[]> GENRLTXT_OWN = new HashMap<>();
    private static final Map<Integer, String> GENRLTXT_FMT = new HashMap<>();
    static {
        GENRLTXT_OWN.put(709, new String[]{"Heldenaustausch", "Hero Exchange"});
        GENRLTXT_OWN.put(714, new String[]{"Alle Zauber der Stufe in der %s", "All spells of this level in the %s"});
        GENRLTXT_OWN.put(726, new String[]{"Kampagne auswählen", "Select a Campaign"});
        GENRLTXT_OWN.put(731, new String[]{"Zauber abgebrochen", "Spell cancelled"});
        GENRLTXT_OWN.put(732, new String[]{"Ihr besitzt alle Teile für: ", "You possess all components for: "});
        GENRLTXT_OWN.put(733, new String[]{"Dieses Artefakt zerlegen?", "Disassemble this artifact?"});
        GENRLTXT_OWN.put(740, new String[]{"Zufallskarte", "Random Map"});
        GENRLTXT_OWN.put(742, new String[]{"Kein Tutorial vorhanden.", "No tutorial available."});
        GENRLTXT_OWN.put(751, new String[]{"Ungültige Zufallskarten-Optionen.", "Invalid random map options."});
        GENRLTXT_OWN.put(762, new String[]{"Kampagnen", "Campaigns"});
        GENRLTXT_FMT.put(715, "%s");
        GENRLTXT_FMT.put(716, "%s");
        GENRLTXT_FMT.put(717, "%d %s");
        GENRLTXT_FMT.put(718, "%s %s");
        GENRLTXT_FMT.put(719, "%s");
    }

    // HELP.TXT: SoD inserted rows (random map tab, new options, campaign bonuses) and dropped a few RoE ones.
    // Each RoE section goes to its SoD index via an anchor row (first column); overflow is dropped.
    private static final String[] HELP_ANCHOR_NAMES = {"SCENARIO_OPTIONS", "ADVANCED_OPTIONS", "CHAT_DESC",
            "Multiplayer Selection", "ANIMATE_SPELLBOOK_ID,", "Adventure Options", "Recruit"};
    private static final int[] HELP_ANCHOR_SOD = {45, 46, 48, 263, 364, 369, 552};
    static final int SOD_HELP = 640;

    static List<List<String>> helpLayout(List<List<String>> rows) {
        List<int[]> marks = new ArrayList<>();
        marks.add(new int[]{0, 0});
        int start = 0;
        for (int a = 0; a < HELP_ANCHOR_NAMES.length; a++) {
            int found = -1;
            for (int i = start; i < rows.size() && found < 0; i++) {
                List<String> r = rows.get(i);
                if (!r.isEmpty() && r.get(0).trim().replace("\"", "").equals(HELP_ANCHOR_NAMES[a])) found = i;
            }
            check(found >= 0, "HELP anchor " + HELP_ANCHOR_NAMES[a]);
            marks.add(new int[]{found, HELP_ANCHOR_SOD[a]});
            start = found + 1;
        }
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < SOD_HELP; i++) out.add(Arrays.asList("", ""));
        for (int k = 0; k < marks.size(); k++) {
            int src = marks.get(k)[0], dst = marks.get(k)[1];
            int endSrc = k + 1 < marks.size() ? marks.get(k + 1)[0] : rows.size();
            int endDst = k + 1 < marks.size() ? marks.get(k + 1)[1] : SOD_HELP;
            for (int j = 0; j < endSrc - src && dst + j < endDst; j++) out.set(dst + j, rows.get(src + j));
        }
        return out;
    }

    private static void addConflux(List<List<String>> t, int rows) {
        t.add(Arrays.asList("", "", "", "", "", "", "", ""));
        t.add(Arrays.asList("Conflux", "", "", "", "", "", "", ""));
        for (int i = 0; i < rows; i++) t.add(Arrays.asList("0", "0", "0", "0", "0", "0", "0", " Conflux"));
    }

    /** tables: name -> bytes (localized LOD already merged over the base one). Returns file name -> content. */
    public static Map<String, byte[]> convert(Map<String, byte[]> tables, boolean german) {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        java.util.function.Function<String, List<List<String>>> T = name -> {
            byte[] b = tables.get(name);
            check(b != null, "missing " + name);
            List<List<String>> r = rows(latin(b));
            while (!r.isEmpty() && String.join("", r.get(r.size() - 1)).trim().isEmpty()) r.remove(r.size() - 1); // English HD tables end in empty rows
            return r;
        };

        List<List<String>> crt = T.apply("CRTRAITS.TXT");
        check(crt.get(1).get(19).equals("Shots") && crt.get(1).get(20).equals("Low"), "CRTRAITS");
        crt = insertCol(crt, 20, "Spells", "0");
        out.put("CRTRAITS.TXT", creaturesLayout(crt, r -> renamed(r, PH, PH),
                r -> german ? renamed(r, "Pfeilturm", "Pfeiltürme") : renamed(r, "Arrow Tower", "Arrow Towers")));
        out.put("CRANIM.TXT", creaturesLayout(T.apply("CRANIM.TXT"), ArrayList::new, ArrayList::new));

        List<List<String>> art = T.apply("ARTRAITS.TXT");
        check(art.get(1).get(7).equals("Misc 4") && art.get(1).get(10).equals("Misc 1"), "ARTRAITS");
        List<List<String>> art2 = new ArrayList<>();
        for (int k = 0; k < art.size(); k++) {
            List<String> r = art.get(k);
            if (r.size() < 7) { art2.add(r); continue; }
            List<String> nr = new ArrayList<>(r.subList(0, 7));
            nr.add(k == 1 ? "Misc 5" : k > 1 ? r.get(10) : "");
            nr.addAll(r.subList(7, r.size()));
            art2.add(nr);
        }
        List<List<String>> recs = art2.subList(2, art2.size());
        check(recs.size() == 127, "artifacts " + recs.size());
        List<String> ph = renamed(recs.get(recs.size() - 1), PH);
        ph.set(ph.size() - 2, "S");
        ph.set(ph.size() - 1, "");
        List<List<String>> artOut = new ArrayList<>(art2);
        while (artOut.size() - 2 < SOD_ARTIFACTS) artOut.add(new ArrayList<>(ph));
        out.put("ARTRAITS.TXT", artOut);
        List<List<String>> ev = new ArrayList<>(T.apply("ARTEVENT.TXT"));
        while (ev.size() < SOD_ARTIFACTS) ev.add(new ArrayList<>(Arrays.asList("")));
        out.put("ARTEVENT.TXT", ev);

        List<List<String>> hc = T.apply("HCTRAITS.TXT");
        check(hc.get(1).get(49).equals("Fortress"), "HCTRAITS");
        hc = new ArrayList<>(insertCol(hc, 50, "Conflux", "0"));
        List<String> lastClass = hc.get(hc.size() - 1);
        while (hc.size() - 2 < SOD_CLASSES) hc.add(renamed(lastClass, PH));
        out.put("HCTRAITS.TXT", hc);

        List<List<String>> ho = new ArrayList<>(T.apply("HOTRAITS.TXT"));
        List<String> firstHero = ho.get(2);
        while (ho.size() - 2 < SOD_HEROES) ho.add(renamed(firstHero, PH));
        out.put("HOTRAITS.TXT", ho);
        List<List<String>> hs = new ArrayList<>(T.apply("HEROSPEC.TXT"));
        while (hs.size() - 2 < SOD_HEROES) hs.add(new ArrayList<>(Arrays.asList(PH, "", "")));
        out.put("HEROSPEC.TXT", hs);
        List<List<String>> bios = T.apply("HEROBIOS.TXT");
        List<List<String>> nb = new ArrayList<>();
        for (int i = 0; i < SOD_HEROES; i++) nb.add(new ArrayList<>(Arrays.asList("")));
        for (int i = 0; i < bios.size(); i++) nb.set(i == 128 ? 146 : i == 129 ? 152 : i, bios.get(i)); // campaign bios
        out.put("HEROBIOS.TXT", nb);

        List<List<String>> sp = T.apply("SPTRAITS.TXT");
        check(sp.get(1).get(23).equals("Fortress"), "SPTRAITS");
        sp = new ArrayList<>(insertCol(sp, 24, "Conflux", "0"));
        while (sp.get(sp.size() - 1).isEmpty() || sp.get(sp.size() - 1).get(0).trim().isEmpty()) sp.remove(sp.size() - 1);
        sp.add(renamed(sp.get(sp.size() - 1), PH, PH));
        out.put("SPTRAITS.TXT", sp);

        List<List<String>> at = T.apply("ARRAYTXT.TXT");
        check(at.get(83).get(0).equals("cMoraleInfo") && at.get(122).get(0).equals("cNewTurn")
                && at.get(222).get(0).equals("NewLoadSave"), "ARRAYTXT");
        List<List<String>> at2 = new ArrayList<>(at.subList(0, 83));
        at2.add(Arrays.asList(""));
        at2.addAll(at.subList(83, 122));
        for (int i = 0; i < 4; i++) at2.add(Arrays.asList(""));
        at2.addAll(at.subList(122, 222));
        at2.add(Arrays.asList(PH));
        at2.addAll(at.subList(222, at.size()));
        out.put("ARRAYTXT.TXT", at2);

        List<List<String>> gt = T.apply("GENRLTXT.TXT");
        check(gt.size() >= 1 + ROE_GENRLTXT, "GENRLTXT");
        List<List<String>> gt2 = new ArrayList<>(gt.subList(0, 1 + ROE_GENRLTXT));
        for (int i = ROE_GENRLTXT; i < SOD_GENRLTXT; i++) {
            String[] own = GENRLTXT_OWN.get(i);
            gt2.add(Arrays.asList(own != null ? own[german ? 0 : 1] : GENRLTXT_FMT.getOrDefault(i, "")));
        }
        out.put("GENRLTXT.TXT", gt2);

        List<List<String>> bn = T.apply("BLDGNEUT.TXT");
        List<List<String>> bn2 = new ArrayList<>();
        for (int i = 0; i < bn.size(); i++) {
            List<String> r = bn.get(i);
            bn2.add(i > 18 && !r.isEmpty() && r.get(0).trim().isEmpty() ? renamed(r, bn.get(16).get(0)) : r);
        }
        out.put("BLDGNEUT.TXT", bn2);

        out.put("HELP.TXT", helpLayout(T.apply("HELP.TXT")));

        // BUILDING.TXT (costs): VCMI reads 9 factions; zero-cost Conflux blocks after Fortress in the unique and
        // dwelling sections, otherwise every later cost is read from the wrong row
        List<List<String>> bd = T.apply("BUILDING.TXT");
        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < bd.size(); i++) if (!bd.get(i).isEmpty() && bd.get(i).get(0).trim().equals("Fortress")) heads.add(i);
        check(heads.size() == 2 && bd.get(heads.get(0) + 10).get(0).trim().isEmpty(), "BUILDING");
        List<List<String>> bd2 = new ArrayList<>(bd.subList(0, heads.get(0) + 10));
        addConflux(bd2, 9);
        bd2.addAll(bd.subList(heads.get(0) + 10, heads.get(1) + 15));
        addConflux(bd2, 14);
        bd2.addAll(bd.subList(heads.get(1) + 15, bd.size()));
        out.put("BUILDING.TXT", bd2);

        List<List<String>> tt = new ArrayList<>(T.apply("TOWNTYPE.TXT"));
        check(tt.size() == 8, "TOWNTYPE");
        tt.add(Arrays.asList(PH));
        out.put("TOWNTYPE.TXT", tt);
        List<List<String>> tn = new ArrayList<>(T.apply("TOWNNAME.TXT"));
        for (int i = 0; i < 16; i++) tn.add(Arrays.asList(PH));
        out.put("TOWNNAME.TXT", tn);

        String tents = german ? "Hellblau,Grün,Rot,Dunkelblau,Braun,Lila,Weiß,Schwarz"
                : "Light Blue,Green,Red,Dark Blue,Brown,Purple,White,Black";
        List<List<String>> tc = new ArrayList<>();
        for (String w : tents.split(",")) tc.add(Arrays.asList(w));
        out.put("TENTCOLR.TXT", tc);

        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<String>>> e : out.entrySet())
            files.put(e.getKey(), dump(e.getValue()).getBytes(StandardCharsets.ISO_8859_1));
        return files;
    }

    // ------------------------------------------------------------------ campaigns

    private static final String[] CAMPAIGNS = {"GOOD1", "GOOD2", "GOOD3", "EVIL1", "EVIL2", "NEUTRAL1", "SECRET1"};
    private static final String[] SETS = {"good1", "good2", "good3", "evil1", "evil2", "neutral1", "secret1"};
    // region art layout of the RoE campaign maps (from VCMI config/campaignRegions.json): prefix, then infix,x,y
    private static final String[][] REGIONS = {
            {"G1", "A", "57", "314", "B", "137", "309", "C", "44", "163"},
            {"G2", "A", "56", "90", "B", "316", "49", "C", "54", "378", "D", "151", "126"},
            {"G3", "A", "289", "376", "B", "60", "147", "C", "131", "202"},
            {"E1", "A", "270", "332", "B", "138", "113", "C", "26", "70"},
            {"E2", "A", "131", "202", "B", "60", "145", "C", "92", "261", "D", "218", "307"},
            {"N1", "A", "42", "94", "B", "309", "290", "CD", "188", "202"},
            {"S1", "A", "263", "199", "B", "182", "210", "C", "82", "152"},
    };

    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') continue;
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) continue;
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static Map<String, List<String>> sections(String txt) {
        Map<String, List<String>> out = new HashMap<>();
        List<String> cur = null;
        for (String line : txt.split("\r\n")) {
            if (line.startsWith("//")) {
                cur = new ArrayList<>();
                out.put(line.substring(2).trim(), cur);
            } else if (cur != null && !line.trim().isEmpty()) {
                cur.add(line.trim());
            }
        }
        return out;
    }

    private static String cp1252(byte[] b) {
        return new String(b, java.nio.charset.Charset.forName("windows-1252"));
    }

    /** Repacks RoE 1.0 campaigns found in tables into outDir/*.vcmp. Returns the number written. */
    public static int campaigns(Map<String, byte[]> tables, File outDir) throws IOException {
        byte[] ct = tables.get("CAMPTEXT.TXT"), cd = tables.get("CAMPDIAG.TXT");
        if (ct == null || cd == null) return 0;
        Map<String, List<String>> names = sections(cp1252(ct));
        Map<String, String> diag = new HashMap<>();
        for (String line : cp1252(cd).split("\r\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) diag.put(line.substring(0, tab).trim().toLowerCase(java.util.Locale.ROOT), line.substring(tab + 1).trim().replaceAll("^\"+|\"+$", ""));
        }
        outDir.mkdirs();
        int written = 0;
        for (String file : CAMPAIGNS) {
            byte[] d = tables.get(file + ".H3C");
            if (d == null || le(d, 0) != 1) continue;
            ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            bb.position(4);
            int region = bb.get() & 0xFF;
            String name = cp1252(str(bb)), desc = cp1252(str(bb));
            List<int[]> sc = new ArrayList<>(); // size, preconditions
            while (!(d[bb.position()] == 0x1f && d[bb.position() + 1] == (byte) 0x8b)) {
                str(bb);
                int size = bb.getInt(), pre = bb.get() & 0xFF;
                if (size > 0) sc.add(new int[]{size, pre});
            }
            String rs = SETS[region - 1];
            String[] reg = REGIONS[region - 1];
            String base = Character.toUpperCase(rs.charAt(0)) + rs.substring(1, rs.length() - 1);
            List<String> regionNames = names.getOrDefault(base + " " + rs.charAt(rs.length() - 1) + " Map Region Names", new ArrayList<>());

            StringBuilder h = new StringBuilder("{\"version\":1,\"name\":").append(js(name)).append(",\"description\":").append(js(desc))
                    .append(",\"allowDifficultySelection\":true,\"regions\":{\"prefix\":").append(js(reg[0]))
                    .append(",\"colorSuffixLength\":0,\"desc\":[");
            for (int i = 0; i < sc.size(); i++) {
                h.append(i > 0 ? "," : "").append("{\"infix\":").append(js(reg[1 + i * 3])).append(",\"x\":").append(reg[2 + i * 3])
                        .append(",\"y\":").append(reg[3 + i * 3]).append('}');
            }
            h.append("]},\"scenarios\":[");
            ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(zipBytes)) {
                for (int i = 0; i < sc.size(); i++) {
                    char letter = "abcd".charAt(i);
                    String key = rs + letter;
                    String brief = diag.get(key);
                    if (brief == null) {
                        for (Map.Entry<String, String> e : diag.entrySet()) if (e.getKey().startsWith(key + "/")) brief = e.getValue();
                    }
                    String video = base.equals("Good") || base.equals("Evil") ? base + rs.charAt(rs.length() - 1) + letter : base + Character.toUpperCase(letter);
                    String epi = diag.get(key + "p");
                    StringBuilder pre = new StringBuilder();
                    for (int j = 0; j < 8; j++) if ((sc.get(i)[1] >> j & 1) != 0) pre.append(pre.length() > 0 ? "," : "").append(j);
                    h.append(i > 0 ? "," : "").append("{\"map\":\"").append(i).append("\",\"preconditions\":[").append(pre)
                            .append("],\"color\":0,\"difficulty\":1,\"regionText\":").append(js(i < regionNames.size() ? regionNames.get(i) : ""))
                            .append(",\"prolog\":").append(brief == null ? "{}" : "{\"text\":" + js(brief) + ",\"video\":[" + js(video) + "]}")
                            .append(",\"epilog\":").append(epi == null ? "{}" : "{\"text\":" + js(epi) + ",\"video\":[" + js(video + "p1") + "," + js(video + "p2") + "]}")
                            .append(",\"heroKeeps\":[],\"keepCreatures\":[],\"startOptions\":\"none\",\"bonuses\":[]}");
                    byte[] gz = Arrays.copyOfRange(d, bb.position(), bb.position() + sc.get(i)[0]);
                    bb.position(bb.position() + sc.get(i)[0]);
                    z.putNextEntry(new ZipEntry(i + ".h3m"));
                    z.write(gunzip(gz));
                    z.closeEntry();
                }
                h.append("]}");
                z.putNextEntry(new ZipEntry("header.json"));
                z.write(h.toString().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
            check(bb.position() == d.length, file + " size");
            try (FileOutputStream fo = new FileOutputStream(new File(outDir, file + ".vcmp"))) {
                zipBytes.writeTo(fo);
            }
            written++;
        }
        return written;
    }

    private static byte[] str(ByteBuffer bb) {
        byte[] s = new byte[bb.getInt()];
        bb.get(s);
        return s;
    }

    private static byte[] gunzip(byte[] in) throws IOException {
        try (InputStream g = new GZIPInputStream(new ByteArrayInputStream(in))) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = g.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    // ------------------------------------------------------------------ desktop self-check

    /** java RoeBridge.java <H3bitmap.lod> [<H3pbitma.lod>] <outDir> : writes Data/*.TXT + campaigns. */
    public static void main(String[] a) throws IOException {
        Map<String, byte[]> t = readLod(new File(a[0]), ".TXT", ".H3C");
        boolean german = a.length == 3;
        if (german) t.putAll(readLod(new File(a[1]), ".TXT", ".H3C"));
        File out = new File(a[a.length - 1]);
        new File(out, "Data").mkdirs();
        for (Map.Entry<String, byte[]> e : convert(t, german).entrySet()) {
            try (FileOutputStream f = new FileOutputStream(new File(out, "Data/" + e.getKey()))) {
                f.write(e.getValue());
            }
        }
        System.out.println("campaigns: " + campaigns(t, new File(out, "campaigns")));
    }
}
