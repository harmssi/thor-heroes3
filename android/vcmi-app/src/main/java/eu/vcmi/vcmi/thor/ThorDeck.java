package eu.vcmi.vcmi.thor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thor Edition deck on the second screen (1240x1080 layout units, scaled to the view), in the
 * look of Heroes III: the game's own stone texture, buttons, portraits, icons and fonts, read at
 * runtime from the player's game files (see {@link H3Art}); nothing of it is in the app.
 *
 * Adventure map: resources and date, minimap (tap = jump), heroes and towns with portraits
 * (tap = select, again = open), army (tap two slots = move / swap / merge, hold = split half),
 * and four pages of commands: the side panel buttons, adventure spells, map tools, game.
 * Town: the town's buildings and armies. Any window: accept / cancel / close / arrows.
 * Battle: turn order, active unit, the hero's spells, log, the battle buttons, speed.
 */
final class ThorDeck extends View implements ThorBridge.Listener {
    private static final float W = 1240f;
    private static final long HOLD_MS = 450;

    private final boolean de;
    private final Paint mapPaint = new Paint();
    private final Paint line = new Paint();
    private final List<Hit> hits = new ArrayList<>();
    private Hit pressed;
    private long pressedAt;

    private JSONObject state = new JSONObject();
    private Bitmap minimap;
    private float s = 1f;
    private int tab;
    private int selSlot = -1;

    private static final class Hit {
        final RectF r;
        final String cmd;
        final String hold;
        Hit(RectF r, String cmd, String hold) { this.r = r; this.cmd = cmd; this.hold = hold; }
    }

    ThorDeck(Context ctx) {
        super(ctx);
        de = ctx.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("de");
        mapPaint.setFilterBitmap(false);
        setBackgroundColor(H3Art.STONE_DARK);
    }

    void start() {
        H3Art.retry();
        ThorBridge.setShown(true, this);
        invalidate();
    }

    void stop() {
        ThorBridge.setShown(false, null);
    }

    private String t(String en, String deText) {
        return de ? deText : en;
    }

    @Override
    public void onState(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("minimap")) {
                decodeMinimap(o.getString("minimap"), o.optInt("w"), o.optInt("h"));
                return;
            }
            state = o;
        } catch (Exception ignored) {
            return;
        }
        invalidate();
    }

    @Override
    public void onArt(String key, int w, int h, byte[] rgba, int[] meta) {
        H3Art.put(key, w, h, rgba, meta);
        invalidate();
    }

    private void decodeMinimap(String b64, int w, int h) {
        byte[] rgb = Base64.decode(b64, Base64.DEFAULT);
        if (w <= 0 || h <= 0 || rgb.length < w * h * 3) {
            return;
        }
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xFF000000 | (rgb[i * 3] & 0xFF) << 16 | (rgb[i * 3 + 1] & 0xFF) << 8 | (rgb[i * 3 + 2] & 0xFF);
        }
        minimap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        invalidate();
    }

    // ---------------------------------------------------------------- helpers

    private RectF r(float l, float tp, float rt, float b) {
        return new RectF(l * s, tp * s, rt * s, b * s);
    }

    private void tx(Canvas c, String font, String str, float x, float y, float lineH, int color, int align) {
        H3Art.text(c, font, str, x * s, y * s, lineH * s, color, align);
    }

    private String fit(String font, String str, float lineH, float max) {
        return H3Art.fit(font, str, lineH * s, max * s);
    }

    private boolean down(String cmd) {
        return pressed != null && pressed.cmd.equals(cmd);
    }

    private void hit(RectF r, String cmd) {
        hits.add(new Hit(r, cmd, null));
    }

    private boolean can(String what) {
        JSONObject can = state.optJSONObject("can");
        return can != null && can.optBoolean(what);
    }

    /** Text button; inactive ones are greyed and do nothing. */
    private void button(Canvas c, RectF r, String label, String cmd, boolean enabled, boolean lit) {
        H3Art.button(c, r, label, 2 * s, enabled && down(cmd), enabled, lit);
        if (enabled) hit(r, cmd);
    }

    /** Rows of text buttons: {label, command, "0" = inactive}. */
    private void grid(Canvas c, String[][] b, int cols, float top, float bottom) {
        int rows = (b.length + cols - 1) / cols;
        float gap = 8, bw = (W - 32 - (cols - 1) * gap) / cols, bh = (bottom - top - (rows - 1) * gap) / rows;
        for (int i = 0; i < b.length; i++) {
            float x = 16 + (i % cols) * (bw + gap), y = top + (i / cols) * (bh + gap);
            button(c, r(x, y, x + bw, y + bh), b[i][0], b[i][1], b[i].length < 3 || !b[i][2].equals("0"), false);
        }
    }

    /** One of the game's own picture buttons (frames: normal, pressed, blocked), with a caption below. */
    private void artButton(Canvas c, float x, float y, float w, float h, String def, String label, String cmd, boolean enabled) {
        RectF br = r(x, y, x + w, y + h);
        int frame = !enabled ? 2 : down(cmd) ? 1 : 0;
        // the adventure map buttons carry the player's colour, as in the game
        String key = "def:" + def + ":" + frame + (def.startsWith("IAM") ? ":" + state.optInt("player") : "");
        if (!H3Art.picture(c, key, br, true, false)) {
            H3Art.button(c, br, "", 2 * s, enabled && down(cmd), enabled, false);
        }
        tx(c, "small", fit("small", label, 22, w + 8), x + w / 2, y + h + 4, 22, enabled ? H3Art.GOLD : H3Art.GREY, 1);
        if (enabled) hit(br, cmd);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        s = getWidth() / W;
        hits.clear();
        H3Art.stone(c, new RectF(0, 0, getWidth(), getHeight()), s);
        String mode = state.optString("mode", "menu");
        if (mode.equals("menu")) {
            drawMenu(c);
        } else if (mode.equals("battle")) {
            drawBattle(c, state.optJSONObject("battle"));
        } else {
            drawAdventure(c, mode);
        }
        if (pressed != null) {
            postInvalidateDelayed(120); // repaint while a finger is down (button faces, hold)
        }
    }

    private void drawMenu(Canvas c) {
        RectF box = r(220, 330, 1020, 720);
        H3Art.well(c, box, 2 * s);
        tx(c, "big", "Heroes III", W / 2, 390, 80, H3Art.GOLD, 1);
        tx(c, "big", "Thor Edition", W / 2, 480, 60, H3Art.WHITE, 1);
        tx(c, "medium", t("for VCMI, an open-source engine for Heroes III", "für VCMI, eine Open-Source-Engine für Heroes III"),
                W / 2, 570, 32, H3Art.WHITE, 1);
        tx(c, "medium", t("Choose a game on the top screen", "Spiel auf dem oberen Bildschirm wählen"), W / 2, 640, 32, H3Art.GOLD, 1);
    }

    private void resourceBar(Canvas c) {
        JSONArray res = state.optJSONArray("res");
        for (int i = 0; i < 7; i++) {
            float x = 16 + i * 146;
            H3Art.well(c, r(x, 10, x + 140, 72), 2 * s);
            if (!H3Art.picture(c, "def:RESOURCE:" + i, r(x + 6, 15, x + 58, 67), true, false)) {
                tx(c, "small", (de ? RES_DE : RES_EN)[i], x + 8, 14, 20, H3Art.GREY, 0);
            }
            tx(c, "medium", res == null ? "-" : String.valueOf(res.optLong(i)), x + 132, 25, 32, i == 6 ? H3Art.GOLD : H3Art.WHITE, 2);
        }
        H3Art.well(c, r(1038, 10, 1224, 72), 2 * s);
        tx(c, "small", t("Month ", "Monat ") + state.optInt("month") + t(", Week ", ", Woche ") + state.optInt("week"),
                1131, 15, 24, H3Art.WHITE, 1);
        tx(c, "small", t("Day ", "Tag ") + state.optInt("day"), 1131, 41, 24, H3Art.GOLD, 1);
    }

    private static final String[] RES_EN = {"Wood", "Merc", "Ore", "Sulf", "Crys", "Gems", "Gold"};
    private static final String[] RES_DE = {"Holz", "Queck", "Erz", "Schw", "Krist", "Edel", "Gold"};

    private void drawAdventure(Canvas c, String mode) {
        boolean live = mode.equals("adventure"); // selection and army orders only act on the plain map
        resourceBar(c);

        // minimap
        RectF mp = r(16, 84, 456, 524);
        H3Art.well(c, mp, 2 * s);
        JSONObject map = state.optJSONObject("map");
        if (map != null && minimap != null && minimap.getWidth() == map.optInt("w")) {
            float box = 420, bx = 26, by = 94;
            float k = box / Math.max(minimap.getWidth(), minimap.getHeight());
            float mw = minimap.getWidth() * k, mh = minimap.getHeight() * k;
            RectF dst = r(bx + (box - mw) / 2, by + (box - mh) / 2, bx + (box + mw) / 2, by + (box + mh) / 2);
            c.drawBitmap(minimap, null, dst, mapPaint);
            JSONArray v = map.optJSONArray("view");
            if (v != null && v.optInt(2) > 0) {
                float fx = dst.left + v.optInt(0) * k * s, fy = dst.top + v.optInt(1) * k * s;
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(2 * s);
                line.setColor(H3Art.WHITE);
                c.drawRect(Math.max(dst.left, fx), Math.max(dst.top, fy),
                        Math.min(dst.right, fx + v.optInt(2) * k * s), Math.min(dst.bottom, fy + v.optInt(3) * k * s), line);
            }
            if (live) hit(dst, "minimap");
        }

        JSONArray heroes = state.optJSONArray("heroes");
        JSONArray towns = state.optJSONArray("towns");
        portraits(c, heroes, 84, 298, true, live);
        portraits(c, towns, 310, 524, false, live);

        JSONObject hero = null, town = null;
        for (int i = 0; heroes != null && i < heroes.length(); i++) {
            if (heroes.optJSONObject(i).optBoolean("selected")) hero = heroes.optJSONObject(i);
        }
        for (int i = 0; towns != null && i < towns.length(); i++) {
            if (towns.optJSONObject(i).optBoolean("selected")) town = towns.optJSONObject(i);
        }
        army(c, live);
        info(c, hero, town, live);

        if (mode.equals("town")) {
            townPage(c, town);
        } else if (!live) {
            windowPage(c, 716);
        } else {
            String[] tabs = {t("Commands", "Befehle"), t("Adventure Spells", "Abenteuerzauber"), t("Map", "Karte"), t("Game", "Spiel")};
            for (int i = 0; i < 4; i++) {
                float x = 16 + i * 304;
                RectF tr = r(x, 716, x + 296, 762);
                H3Art.button(c, tr, tabs[i], 2 * s, down("ui:tab " + i), true, tab == i);
                hit(tr, "ui:tab " + i);
            }
            if (tab == 0) commandPage(c, hero);
            else if (tab == 1) spellPage(c, hero);
            else if (tab == 2) mapPage(c);
            else gamePage(c);
        }
    }

    /** Heroes (portrait between movement and spell point bars, as in the game's hero list) or towns. */
    private void portraits(Canvas c, JSONArray list, float top, float bottom, boolean heroes, boolean live) {
        H3Art.well(c, r(468, top, 1224, bottom), 2 * s);
        int n = list == null ? 0 : Math.min(list.length(), 16);
        if (n == 0) {
            tx(c, "medium", heroes ? t("No heroes", "Keine Helden") : t("No towns", "Keine Städte"), 846, (top + bottom) / 2 - 16, 32, H3Art.GREY, 1);
            return;
        }
        int rows = n > 8 ? 2 : 1, cols = Math.max(4, (n + rows - 1) / rows);
        float cw = 744f / cols, ch = (bottom - top - 12) / rows, bar = heroes ? Math.min(12, cw * 0.09f) : 0;
        float nameH = rows == 1 ? 24 : 18;
        float ph = Math.min(ch - nameH - 10, (cw - 2 * bar - 14) * 64f / 58f), pw = ph * 58f / 64f;
        for (int i = 0; i < n; i++) {
            JSONObject o = list.optJSONObject(i);
            float cx = 474 + (i % cols) * cw + cw / 2, y = top + 8 + (i / cols) * ch;
            RectF pr = r(cx - pw / 2, y, cx + pw / 2, y + ph);
            boolean sel = o.optBoolean("selected");
            if (sel) {
                H3Art.fill(c, r(cx - pw / 2 - bar - 5, y - 3, cx + pw / 2 + bar + 5, y + ph + 3), H3Art.FRAME_HI);
            }
            String key = "def:" + (heroes ? "PortraitsLarge:" : "ITPT:") + o.optInt("icon");
            if (!H3Art.picture(c, key, pr, false, heroes && o.optBoolean("sleeping"))) {
                H3Art.fill(c, pr, 0xFF2A1B0E);
            }
            if (heroes) {
                H3Art.vbar(c, r(cx - pw / 2 - bar - 2, y, cx - pw / 2 - 2, y + ph), o.optInt("mp") / (float) Math.max(1, o.optInt("mpMax")), 0xFF2E7D1E, 0xFFB8E04A);
                H3Art.vbar(c, r(cx + pw / 2 + 2, y, cx + pw / 2 + bar + 2, y + ph), o.optInt("mana") / (float) Math.max(1, o.optInt("manaMax")), 0xFF1C2E9C, 0xFF6FA8FF);
                if (o.optBoolean("sleeping")) {
                    tx(c, "big", "Zz", cx, y + ph / 2 - 20, 40, H3Art.WHITE, 1);
                }
            }
            tx(c, "small", fit("small", o.optString("name"), nameH, cw - 6), cx, y + ph + 5, nameH, sel ? H3Art.GOLD : H3Art.WHITE, 1);
            if (live) hit(r(cx - cw / 2, y - 4, cx + cw / 2, y + ch - 4), (heroes ? "hero " : "town ") + o.optInt("id"));
        }
    }

    private void army(Canvas c, boolean live) {
        H3Art.well(c, r(16, 536, 892, 704), 2 * s);
        JSONArray army = state.optJSONArray("army");
        boolean any = false;
        for (int i = 0; i < 7; i++) {
            float x = 26 + i * 123;
            RectF sr = r(x, 544, x + 116, 672);
            JSONObject st = null;
            for (int k = 0; army != null && k < army.length(); k++) {
                if (army.optJSONObject(k).optInt("slot") == i) st = army.optJSONObject(k);
            }
            H3Art.fill(c, sr, 0xFF140D07);
            if (st != null) {
                any = true;
                if (!H3Art.picture(c, "def:TWCRPORT:" + st.optInt("icon"), sr, true, false)) {
                    tx(c, "small", fit("small", st.optString("name"), 20, 110), x + 58, 580, 20, H3Art.WHITE, 1);
                }
                H3Art.fill(c, r(x, 644, x + 116, 672), 0xA0000000);
                tx(c, "medium", String.valueOf(st.optInt("count")), x + 110, 644, 28, H3Art.WHITE, 2);
            }
            H3Art.frame(c, r(x - 3, 541, x + 119, 675), s, true);
            if (i == selSlot) {
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(4 * s);
                line.setColor(H3Art.FRAME_HI);
                c.drawRect(r(x - 1, 543, x + 117, 673), line);
            }
            if (live) hits.add(new Hit(sr, "ui:slot " + i + (st != null ? " 1" : " 0"), "ui:split " + i));
        }
        if (!any) selSlot = -1;
        String hint = !live ? state.optString("armyOf")
                : selSlot >= 0 ? t("Tap a slot: move, swap or merge - hold a slot: split half off", "Platz antippen: ziehen, tauschen, vereinen - halten: Hälfte abteilen")
                : t("Army: tap a stack to pick it up", "Armee: Trupp antippen zum Aufnehmen");
        tx(c, "small", fit("small", hint, 22, 850), 454, 678, 22, selSlot >= 0 ? H3Art.GOLD : H3Art.GREY, 1);
    }

    private void info(Canvas c, JSONObject hero, JSONObject town, boolean live) {
        H3Art.well(c, r(900, 536, 1224, 704), 2 * s);
        if (hero != null) {
            tx(c, "big", fit("big", hero.optString("name"), 36, 300), 1062, 542, 36, H3Art.GOLD, 1);
            tx(c, "small", t("Level ", "Stufe ") + hero.optInt("level"), 1062, 578, 22, H3Art.WHITE, 1);
            String[] names = de ? new String[]{"Ang", "Vtd", "Kra", "Wis"} : new String[]{"Att", "Def", "Pow", "Kno"};
            String[] keys = {"attack", "defense", "power", "knowledge"};
            for (int i = 0; i < 4; i++) {
                float x = 942 + i * 80;
                tx(c, "small", names[i], x, 604, 20, H3Art.GREY, 1);
                tx(c, "medium", String.valueOf(hero.optInt(keys[i])), x, 624, 28, H3Art.WHITE, 1);
            }
            H3Art.hbar(c, r(912, 660, 1058, 672), hero.optInt("mp") / (float) Math.max(1, hero.optInt("mpMax")), 0xFF2E7D1E, 0xFFB8E04A);
            H3Art.hbar(c, r(1066, 660, 1212, 672), hero.optInt("mana") / (float) Math.max(1, hero.optInt("manaMax")), 0xFF1C2E9C, 0xFF6FA8FF);
            tx(c, "small", t("Move ", "Bew. ") + hero.optInt("mp"), 985, 675, 20, H3Art.WHITE, 1);
            tx(c, "small", t("Mana ", "Mana ") + hero.optInt("mana") + "/" + hero.optInt("manaMax"), 1139, 675, 20, H3Art.WHITE, 1);
        } else if (town != null) {
            tx(c, "big", fit("big", town.optString("name"), 36, 300), 1062, 560, 36, H3Art.GOLD, 1);
            tx(c, "small", town.optBoolean("built") ? t("Built today", "Heute gebaut") : t("Can build today", "Kann heute bauen"),
                    1062, 610, 22, H3Art.WHITE, 1);
            if (live) tx(c, "small", t("Tap the town again to enter", "Stadt nochmal antippen: betreten"), 1062, 650, 20, H3Art.GREY, 1);
        }
    }

    private void commandPage(Canvas c, JSONObject hero) {
        boolean sleeping = hero != null && hero.optBoolean("sleeping");
        boolean under = state.optJSONObject("map") != null && state.optJSONObject("map").optJSONArray("view") != null
                && state.optJSONObject("map").optJSONArray("view").optInt(4) > 0;
        float x = 17, y = 774;
        artButton(c, x, y, 192, 96, "IAM000", t("Next Hero", "Nächster Held"), "key adventureNextHero", can("next"));
        artButton(c, x += 198, y, 192, 96, "IAM001", t("End Turn", "Zug beenden"), "key adventureEndTurn", can("endTurn"));
        artButton(c, x += 198, y, 96, 96, "IAM006", t("Move", "Ziehen"), "key adventureMoveHero", can("move"));
        artButton(c, x += 102, y, 96, 96, sleeping ? "IAM011" : "IAM005", sleeping ? t("Wake", "Wecken") : t("Sleep", "Schlafen"),
                "key adventureToggleSleep", can("hero"));
        artButton(c, x += 102, y, 96, 96, "IAM007", t("Spells", "Zauber"), "key adventureCastSpell", can("hero"));
        artButton(c, x += 102, y, 96, 96, "IAM002", t("Kingdom", "Königreich"), "key adventureKingdomOverview", true);
        artButton(c, x += 102, y, 96, 96, "IAM004", t("Quests", "Aufgaben"), "key adventureQuestLog", can("quests"));
        artButton(c, x += 102, y, 96, 96, under ? "IAM003" : "IAM010", under ? t("Surface", "Oberwelt") : t("Caves", "Unterwelt"),
                "key adventureToggleMapLevel", can("level"));
        artButton(c, x += 102, y, 96, 96, "IAM008", t("Adventure", "Abenteuer"), "key adventureGameOptions", true);
        artButton(c, x + 102, y, 96, 96, "IAM009", t("System", "System"), "key globalOptions", true);
        grid(c, new String[][]{
                {t("Next Town", "Nächste Stadt"), "key adventureNextTown"},
                {hero != null ? t("Hero Screen", "Heldenfenster") : t("Enter Town", "Stadt betreten"), "key adventureViewSelected"},
                {t("Visit Again", "Erneut besuchen"), "key adventureVisitObject", can("visit") ? "1" : "0"},
                {t("Dig for the Grail", "Nach dem Gral graben"), "key adventureDigGrail", can("dig") ? "1" : "0"},
                {t("Marketplace", "Marktplatz"), "key adventureMarketplace", can("market") ? "1" : "0"},
                {t("Thieves' Guild", "Diebesgilde"), "key adventureThievesGuild"},
                {t("Puzzle Map", "Rätselkarte"), "key adventureViewPuzzle"},
                {t("Quick Save", "Schnellspeichern"), "key adventureQuickSave", can("quickSave") ? "1" : "0"},
        }, 4, 908, 1068);
    }

    private void spellPage(Canvas c, JSONObject hero) {
        JSONArray sp = state.optJSONArray("spells");
        if (hero == null || sp == null || sp.length() == 0) {
            tx(c, "medium", hero == null ? t("Select a hero to see adventure spells", "Held wählen, um Abenteuerzauber zu sehen")
                    : t("This hero knows no adventure spells", "Dieser Held kennt keine Abenteuerzauber"), W / 2, 900, 32, H3Art.GREY, 1);
            return;
        }
        spellGrid(c, sp, "spell ", 16, 772, 1224, 1068, 4, 3);
    }

    /** Spell icons from the game's spell book with name and cost; tap = cast (target on the top screen). */
    private void spellGrid(Canvas c, JSONArray sp, String cmd, float left, float top, float right, float bottom, int cols, int rows) {
        float gap = 8, cw = (right - left - (cols - 1) * gap) / cols, ch = (bottom - top - (rows - 1) * gap) / rows;
        // castable spells first, the spell book has the rest if the list is longer than the page
        List<JSONObject> list = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < sp.length(); i++) {
                if (sp.optJSONObject(i).optBoolean("ok") == (pass == 0)) list.add(sp.optJSONObject(i));
            }
        }
        for (int i = 0; i < list.size() && i < cols * rows; i++) {
            JSONObject o = list.get(i);
            float x = left + (i % cols) * (cw + gap), y = top + (i / cols) * (ch + gap);
            RectF cell = r(x, y, x + cw, y + ch);
            boolean ok = o.optBoolean("ok");
            String id = cmd + o.optInt("id");
            H3Art.button(c, cell, "", 2 * s, ok && down(id), ok, false);
            float iw = Math.min(ch - 12, cw * 0.4f) * 83f / 65f;
            H3Art.picture(c, "def:Spells:" + o.optInt("icon"), r(x + 8, y + 6, x + 8 + iw, y + ch - 6), true, !ok);
            float tx0 = x + 14 + iw, tw = cw - iw - 22;
            float lh = ch > 80 ? 24 : 20;
            tx(c, "small", fit("small", o.optString("name"), lh, tw), tx0, y + ch / 2 - lh - 1, lh, ok ? H3Art.GOLD : H3Art.GREY, 0);
            tx(c, "small", fit("small", o.optInt("cost") + t(" mana", " Mana"), lh, tw), tx0, y + ch / 2 + 2, lh, ok ? H3Art.WHITE : H3Art.GREY, 0);
            if (ok) hit(cell, id);
        }
    }

    private void mapPage(Canvas c) {
        boolean world = can("worldView");
        grid(c, new String[][]{
                {t("View World", "Weltansicht"), "key adventureViewWorld", world ? "0" : "1"},
                {t("World 1x", "Welt 1x"), "key adventureViewWorld1", world ? "1" : "0"},
                {t("World 2x", "Welt 2x"), "key adventureViewWorld2", world ? "1" : "0"},
                {t("World 4x", "Welt 4x"), "key adventureViewWorld4", world ? "1" : "0"},
                {t("Back to the Map", "Zurück zur Karte"), "key adventureExitWorldView", world ? "1" : "0"},
                {t("Zoom In", "Vergrößern"), "key adventureZoomIn"},
                {t("Zoom Out", "Verkleinern"), "key adventureZoomOut"},
                {t("Zoom Reset", "Zoom zurücksetzen"), "key adventureZoomReset"},
                {t("Surface / Underground", "Oberwelt / Unterwelt"), "key adventureToggleMapLevel", can("level") ? "1" : "0"},
                {t("Grid", "Raster"), "key adventureToggleGrid", world ? "0" : "1"},
                {t("Show Visitable", "Besuchbares zeigen"), "key adventureToggleVisitable", world ? "0" : "1"},
                {t("Show Blocked", "Blockiertes zeigen"), "key adventureToggleBlocked", world ? "0" : "1"},
        }, 4, 772, 1068);
    }

    private void gamePage(Canvas c) {
        grid(c, new String[][]{
                {t("Quick Save", "Schnellspeichern"), "key adventureQuickSave", can("quickSave") ? "1" : "0"},
                {t("Quick Load", "Schnellladen"), "key adventureQuickLoad", can("quickLoad") ? "1" : "0"},
                {t("Save Game", "Spiel speichern"), "key adventureSaveGame"},
                {t("Load Game", "Spiel laden"), "key adventureLoadGame"},
                {t("Scenario Info", "Szenario-Info"), "key adventureViewScenario"},
                {t("Adventure Options", "Abenteuer-Optionen"), "key adventureGameOptions"},
                {t("System Options", "Systemoptionen"), "key globalOptions"},
                {t("Main Menu", "Hauptmenü"), "key adventureMainMenu"},
        }, 4, 772, 1068);
    }

    private void townPage(Canvas c, JSONObject town) {
        tx(c, "big", town == null ? t("Town", "Stadt") : town.optString("name"), W / 2, 718, 40, H3Art.GOLD, 1);
        grid(c, new String[][]{
                {t("Town Hall", "Rathaus"), "key townOpenHall"},
                {t("Fort", "Festung"), "key townOpenFort"},
                {t("Recruit", "Rekrutieren"), "key townOpenRecruitment"},
                {t("Tavern", "Taverne"), "key townOpenTavern"},
                {t("Marketplace", "Marktplatz"), "key townOpenMarket"},
                {t("Mage Guild", "Magiergilde"), "key townOpenMageGuild"},
                {t("Swap Armies", "Armeen tauschen"), "key townSwapArmies"},
                {t("Visiting Hero", "Besuchender Held"), "key townOpenVisitingHero"},
                {t("Garrison Hero", "Garnisonsheld"), "key townOpenGarrisonedHero"},
                {t("Previous Town", "Vorige Stadt"), "key moveUp"},
                {t("Next Town", "Nächste Stadt"), "key moveDown"},
                {t("Leave Town", "Stadt verlassen"), "key globalReturn"},
        }, 4, 772, 1068);
    }

    /** A window of the game is open on the top screen: answer it from here (the keys Enter, Escape, arrows). */
    private void windowPage(Canvas c, float top) {
        tx(c, "medium", t("A window is open on the top screen", "Oben ist ein Fenster offen"), W / 2, top + 6, 32, H3Art.GOLD, 1);
        float y = top + 56, h = 1068 - y;
        button(c, r(16, y, 400, y + h), t("OK / Accept", "OK / Annehmen"), "key globalAccept,globalReturn", true, true);
        button(c, r(408, y, 792, y + h), t("Cancel / Close", "Abbrechen / Schließen"), "key globalCancel,globalReturn", true, false);
        float ax = 800, aw = (1224 - ax - 16) / 3, ah = (h - 8) / 2;
        button(c, r(ax + aw + 8, y, ax + 2 * aw + 8, y + ah), t("Up", "Hoch"), "key moveUp", true, false);
        button(c, r(ax, y + ah + 8, ax + aw, y + h), t("Left", "Links"), "key moveLeft", true, false);
        button(c, r(ax + aw + 8, y + ah + 8, ax + 2 * aw + 8, y + h), t("Down", "Runter"), "key moveDown", true, false);
        button(c, r(ax + 2 * aw + 16, y + ah + 8, 1224, y + h), t("Right", "Rechts"), "key moveRight", true, false);
    }

    private void drawBattle(Canvas c, JSONObject b) {
        if (b == null) b = new JSONObject();
        JSONObject u = b.optJSONObject("unit");

        // hero and spell points
        H3Art.well(c, r(16, 10, 1224, 72), 2 * s);
        if (b.has("hero")) {
            H3Art.picture(c, "def:PortraitsLarge:" + b.optInt("heroIcon"), r(22, 14, 72, 68), false, false);
            tx(c, "big", fit("big", b.optString("hero"), 36, 380), 84, 23, 36, H3Art.GOLD, 0);
            tx(c, "medium", t("Spell points ", "Zauberpunkte ") + b.optInt("mana") + " / " + b.optInt("manaMax"), 1214, 14, 28, H3Art.WHITE, 2);
            H3Art.hbar(c, r(820, 48, 1214, 62), b.optInt("mana") / (float) Math.max(1, b.optInt("manaMax")), 0xFF1C2E9C, 0xFF6FA8FF);
        } else {
            tx(c, "big", t("Battle", "Kampf"), 30, 23, 36, H3Art.GOLD, 0);
        }

        // turn order
        H3Art.well(c, r(16, 84, 1224, 226), 2 * s);
        JSONArray q = b.optJSONArray("queue");
        for (int i = 0; q != null && i < q.length() && i < 13; i++) {
            JSONObject o = q.optJSONObject(i);
            float x = 26 + i * 92;
            // attacker red, defender blue, as the banners on the battlefield
            H3Art.fill(c, r(x - 3, 91, x + 87, 219), o.optInt("side") == 0 ? 0xFFB02818 : 0xFF2848B0);
            if (i == 0) H3Art.fill(c, r(x - 3, 91, x + 87, 219), H3Art.FRAME_HI);
            RectF pr = r(x, 94, x + 84, 187);
            H3Art.fill(c, pr, 0xFF140D07);
            H3Art.picture(c, "def:TWCRPORT:" + o.optInt("icon"), pr, false, o.optInt("turn") > 0);
            H3Art.fill(c, r(x, 189, x + 84, 216), 0xFF000000);
            tx(c, "small", String.valueOf(o.optInt("count")), x + 42, 191, 24, H3Art.WHITE, 1);
        }

        // active unit
        H3Art.well(c, r(16, 238, 420, 700), 2 * s);
        if (u != null) {
            H3Art.picture(c, "def:TWCRPORT:" + u.optInt("icon"), r(28, 250, 144, 378), true, false);
            tx(c, "big", fit("big", u.optString("name"), 32, 250), 156, 262, 32, H3Art.GOLD, 0);
            tx(c, "big", "x " + u.optInt("count"), 156, 310, 40, H3Art.WHITE, 0);
            String[][] rows = {
                    {t("Attack", "Angriff"), String.valueOf(u.optInt("attack"))},
                    {t("Defense", "Verteidigung"), String.valueOf(u.optInt("defense"))},
                    {t("Damage", "Schaden"), u.optInt("dmgMin") + " - " + u.optInt("dmgMax")},
                    {t("Health", "Leben"), u.optInt("hp") + " / " + u.optInt("hpMax")},
                    {t("Speed", "Tempo"), String.valueOf(u.optInt("speed"))},
                    {t("Shots", "Schüsse"), u.optBoolean("shooter") ? String.valueOf(u.optInt("shots")) : "-"},
            };
            for (int i = 0; i < rows.length; i++) {
                float y = 398 + i * 44;
                tx(c, "medium", rows[i][0], 32, y, 28, H3Art.WHITE, 0);
                tx(c, "medium", rows[i][1], 404, y, 28, H3Art.GOLD, 2);
            }
            H3Art.hbar(c, r(30, 668, 406, 684), u.optInt("hp") / (float) Math.max(1, u.optInt("hpMax")), 0xFF8C1C10, H3Art.RED);
        } else {
            tx(c, "medium", t("Waiting for your turn", "Warte auf Euren Zug"), 218, 450, 28, H3Art.GREY, 1);
        }

        // the hero's combat spells
        H3Art.well(c, r(432, 238, 1224, 700), 2 * s);
        JSONArray sp = b.optJSONArray("spells");
        if (sp != null && sp.length() > 0) {
            spellGrid(c, sp, "bspell ", 442, 248, 1214, 690, 3, 6);
        } else {
            tx(c, "medium", b.has("hero") ? t("No combat spells", "Keine Kampfzauber") : t("No hero in this battle", "Kein Held in diesem Kampf"),
                    828, 450, 28, H3Art.GREY, 1);
        }

        // log
        H3Art.well(c, r(16, 712, 1224, 852), 2 * s);
        JSONArray log = b.optJSONArray("log");
        float y = 818;
        for (int i = log == null ? -1 : log.length() - 1; i >= 0 && y > 714; i--) {
            List<String> lines = wrap(clean(log.optString(i)), 26, 1180);
            for (int k = lines.size() - 1; k >= 0 && y > 714; k--) {
                tx(c, "small", lines.get(k), 28, y, 26, i == log.length() - 1 ? H3Art.WHITE : H3Art.GREY, 0);
                y -= 30;
            }
        }

        if (state.optBoolean("modal")) {
            windowPage(c, 858);
            return;
        }
        boolean tactics = b.optBoolean("tactics");
        boolean mine = u != null;
        float x = 16;
        if (tactics) {
            artButton(c, x, 864, 144, 108, "icm011", t("Next Unit", "Nächste Einheit"), "key battleTacticsNext", true);
            artButton(c, x += 152, 864, 144, 108, "icm012", t("Start Battle", "Kampf beginnen"), "key battleTacticsEnd", true);
        } else {
            artButton(c, x, 864, 144, 108, "icm005", t("Spell Book", "Zauberbuch"), "key battleCastSpell", mine && b.has("hero"));
            artButton(c, x += 152, 864, 144, 108, "icm006", t("Wait", "Warten"), "key battleWait", mine);
            artButton(c, x += 152, 864, 144, 108, "icm007", t("Defend", "Verteidigen"), "key battleDefend", mine);
        }
        artButton(c, x += 152, 864, 144, 108, "icm004", t("Auto Combat", "Auto-Kampf"), "key battleAutocombat", true);
        artButton(c, x += 152, 864, 144, 108, "icm002", t("Retreat", "Rückzug"), "key battleRetreat", mine && !tactics);
        artButton(c, x += 152, 864, 144, 108, "icm001", t("Surrender", "Aufgeben"), "key battleSurrender", mine && !tactics);
        artButton(c, x + 152, 864, 144, 108, "icm003", t("Options", "Optionen"), "key globalOptions", true);

        float bx = 1080;
        button(c, r(bx, 864, 1224, 914), t("Unit Info", "Einheit-Info"), "key battleOpenActiveUnit", mine, false);
        button(c, r(bx, 922, 1224, 972), t("Queue on Top", "Reihenfolge oben"), "key battleToggleQueue", true, false);
        tx(c, "small", t("Battle speed", "Kampftempo"), 28, 1024, 24, H3Art.WHITE, 0);
        int speed = b.optInt("speed", 2);
        String[] names = {t("Slow", "Langsam"), t("Normal", "Normal"), t("Fast", "Schnell")};
        for (int i = 0; i < 3; i++) {
            float sx = 190 + i * 208;
            RectF sr = r(sx, 1010, sx + 200, 1068);
            H3Art.button(c, sr, names[i], 2 * s, down("speed " + (i + 1)), true, speed == i + 1);
            hit(sr, "speed " + (i + 1));
        }
        button(c, r(830, 1010, 1224, 1068), t("Hero Statistics", "Heldenwerte"), "key battleToggleHeroesStats", true, false);
    }

    /** VCMI text markup: {highlight} and {#color|text} -> plain text. */
    private static String clean(String str) {
        return str.replaceAll("\\{#[0-9A-Za-z]+\\|", "").replace("{", "").replace("}", "");
    }

    private List<String> wrap(String str, float lineH, float width) {
        List<String> out = new ArrayList<>();
        for (String para : str.split("\n")) {
            String cur = "";
            for (String w : para.split(" ")) {
                String cand = cur.isEmpty() ? w : cur + " " + w;
                if (H3Art.width("small", cand, lineH * s) > width * s && !cur.isEmpty()) {
                    out.add(cur);
                    cur = w;
                } else {
                    cur = cand;
                }
            }
            if (!cur.isEmpty()) out.add(cur);
        }
        return out;
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            pressed = null;
            for (Hit h : hits) {
                if (h.r.contains(e.getX(), e.getY())) pressed = h;
            }
            pressedAt = System.currentTimeMillis();
            invalidate();
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            Hit h = pressed;
            pressed = null;
            invalidate();
            if (h == null || e.getActionMasked() == MotionEvent.ACTION_CANCEL || !h.r.contains(e.getX(), e.getY())) {
                return true;
            }
            boolean held = h.hold != null && System.currentTimeMillis() - pressedAt >= HOLD_MS;
            run(held ? h.hold : h.cmd, h, e);
            performClick();
        }
        return true;
    }

    private void run(String cmd, Hit h, MotionEvent e) {
        if (cmd.equals("minimap")) {
            JSONObject map = state.optJSONObject("map");
            if (map != null) {
                int tx = (int) ((e.getX() - h.r.left) / h.r.width() * map.optInt("w"));
                int ty = (int) ((e.getY() - h.r.top) / h.r.height() * map.optInt("h"));
                ThorBridge.send("tile " + tx + " " + ty);
            }
        } else if (cmd.startsWith("ui:tab ")) {
            tab = Integer.parseInt(cmd.substring(7));
        } else if (cmd.startsWith("ui:slot ") || cmd.startsWith("ui:split ")) {
            // army: first tap picks a stack up, second tap puts it down (move / swap / merge), hold = split half off
            String[] a = cmd.split(" ");
            int slot = Integer.parseInt(a[1]);
            boolean split = a[0].equals("ui:split");
            if (selSlot < 0) {
                boolean filled = h.cmd.endsWith(" 1");
                selSlot = filled ? slot : -1;
            } else if (slot == selSlot) {
                selSlot = -1;
            } else {
                ThorBridge.send("army " + selSlot + " " + slot + (split ? " s" : " m"));
                selSlot = -1;
            }
        } else {
            ThorBridge.send(cmd);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
