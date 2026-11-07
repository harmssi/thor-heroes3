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
 * Thor Edition deck on the second screen (1240x1080 layout units, scaled to the view).
 * Adventure map: minimap (tap = jump), heroes, towns, army, resources, date, turn buttons.
 * Battle: active unit, hero mana, battle log, spellbook/wait/defend/auto/retreat/surrender.
 * Draws only its own shapes and text; no game artwork is shipped or drawn.
 */
final class ThorDeck extends View implements ThorBridge.Listener {
    private static final float W = 1240f;
    private static final float M = 28f;

    private final boolean de = Locale.getDefault().getLanguage().equals("de");
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mapPaint = new Paint();
    private final List<Hit> hits = new ArrayList<>();
    private Hit pressed;
    private long pressedAt;

    private JSONObject state = new JSONObject();
    private Bitmap minimap;
    private int minimapVersion = -1;
    private float s = 1f;

    private static final class Hit {
        final RectF r;
        final String cmd;
        Hit(RectF r, String cmd) { this.r = r; this.cmd = cmd; }
    }

    ThorDeck(Context ctx) {
        super(ctx);
        Ui.init(ctx);
        mapPaint.setFilterBitmap(false);
        setBackgroundColor(Ui.BG);
    }

    void start() {
        ThorBridge.setShown(true, this);
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
                decodeMinimap(o.getString("minimap"), o.optInt("w"), o.optInt("h"), o.optInt("version"));
                return;
            }
            state = o;
        } catch (Exception ignored) {
            return;
        }
        invalidate();
    }

    private void decodeMinimap(String b64, int w, int h, int version) {
        byte[] rgb = Base64.decode(b64, Base64.DEFAULT);
        if (w <= 0 || h <= 0 || rgb.length < w * h * 3) {
            return;
        }
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xFF000000 | (rgb[i * 3] & 0xFF) << 16 | (rgb[i * 3 + 1] & 0xFF) << 8 | (rgb[i * 3 + 2] & 0xFF);
        }
        minimap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        minimapVersion = version;
        invalidate();
    }

    // ---------------------------------------------------------------- drawing

    private RectF r(float l, float tp, float rt, float b) {
        return new RectF(l * s, tp * s, rt * s, b * s);
    }

    private void text(Canvas c, String str, float x, float y, Paint paint) {
        c.drawText(str, x * s, y * s, paint);
    }

    private Paint label(float size, int color) {
        return Ui.label(size * s, color);
    }

    private Paint font(android.graphics.Typeface tf, float size, int color) {
        return Ui.text(tf, size * s, color);
    }

    @Override
    protected void onDraw(Canvas c) {
        s = getWidth() / W;
        hits.clear();
        c.drawColor(Ui.BG);
        String mode = state.optString("mode", "menu");
        if (mode.equals("menu")) {
            drawMenu(c);
            return;
        }
        drawHeader(c, mode);
        if (mode.equals("battle")) {
            drawBattle(c, state.optJSONObject("battle"));
        } else {
            drawAdventure(c, mode);
        }
        if (pressed != null && System.currentTimeMillis() - pressedAt < 180) {
            p.setColor(Ui.alpha(Ui.AMBER, 0.18f));
            c.drawRoundRect(pressed.r, Ui.RADIUS * s, Ui.RADIUS * s, p);
            postInvalidateDelayed(190);
        }
    }

    private void drawMenu(Canvas c) {
        float cx = W / 2f;
        Ui.emblem(c, cx * s, 420 * s, 70 * s, Ui.AMBER, p);
        Paint title = label(40, Ui.TEXT);
        title.setTextAlign(Paint.Align.CENTER);
        text(c, "THOR EDITION", cx, 560, title);
        Paint sub = font(Ui.inter(), 24, Ui.TEXT_2);
        sub.setTextAlign(Paint.Align.CENTER);
        text(c, t("for VCMI · an open-source engine for Heroes III", "for VCMI · Open-Source-Engine für Heroes III"), cx, 606, sub);
        Paint hint = label(16, Ui.TEXT_3);
        hint.setTextAlign(Paint.Align.CENTER);
        text(c, t("CHOOSE A GAME ON THE TOP SCREEN", "SPIEL OBEN AUSWÄHLEN"), cx, 700, hint);
    }

    private void drawHeader(Canvas c, String mode) {
        Ui.emblem(c, 54 * s, 52 * s, 20 * s, Ui.AMBER, p);
        text(c, "THOR EDITION", 88, 48, label(18, Ui.AMBER));
        String sub = mode.equals("battle") ? t("BATTLE", "KAMPF") : mode.equals("town") ? t("TOWN", "STADT")
                : mode.equals("dialog") ? t("WINDOW OPEN · USE TOP SCREEN", "FENSTER OFFEN · OBEN BEDIENEN")
                : t("ADVENTURE MAP", "ABENTEUERKARTE");
        text(c, sub, 88, 74, label(13, Ui.TEXT_3));
        Paint d = label(18, Ui.TEXT);
        d.setTextAlign(Paint.Align.RIGHT);
        text(c, String.format(Locale.ROOT, "%s %d · %s %d · %s %d",
                t("MONTH", "MONAT"), state.optInt("month"), t("WEEK", "WOCHE"), state.optInt("week"),
                t("DAY", "TAG"), state.optInt("day")), W - M, 60, d);
    }

    private static final String[] RES_EN = {"WOOD", "MERCURY", "ORE", "SULFUR", "CRYSTAL", "GEMS", "GOLD"};
    private static final String[] RES_DE = {"HOLZ", "QUECKSILBER", "ERZ", "SCHWEFEL", "KRISTALL", "EDELSTEINE", "GOLD"};

    private void drawAdventure(Canvas c, String mode) {
        // resources
        JSONArray res = state.optJSONArray("res");
        float tw = (W - 2 * M - 6 * 10) / 7f;
        for (int i = 0; i < 7; i++) {
            float x = M + i * (tw + 10);
            Ui.panel(c, r(x, 100, x + tw, 172), 10 * s, Ui.PANEL, Ui.LINE, p);
            text(c, (de ? RES_DE : RES_EN)[i], x + 14, 124, label(11, Ui.TEXT_3));
            text(c, res == null ? "-" : String.valueOf(res.optLong(i)), x + 14, 160, font(Ui.mono(), 26, i == 6 ? Ui.AMBER_2 : Ui.TEXT));
        }

        // minimap
        RectF mp = r(M, 192, 628, 800);
        Ui.panel(c, mp, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, p);
        text(c, t("MINIMAP · TAP TO JUMP", "MINIKARTE · ANTIPPEN = SPRINGEN"), M + 20, 222, label(12, Ui.TEXT_3));
        JSONObject map = state.optJSONObject("map");
        if (map != null && minimap != null && minimap.getWidth() == map.optInt("w")) {
            float box = 544, bx = M + 28, by = 240;
            float k = box / Math.max(minimap.getWidth(), minimap.getHeight());
            float mw = minimap.getWidth() * k, mh = minimap.getHeight() * k;
            RectF dst = r(bx + (box - mw) / 2, by + (box - mh) / 2, bx + (box + mw) / 2, by + (box + mh) / 2);
            c.drawBitmap(minimap, null, dst, mapPaint);
            JSONArray v = map.optJSONArray("view");
            if (v != null && v.optInt(2) > 0) {
                float fx = dst.left + v.optInt(0) * k * s, fy = dst.top + v.optInt(1) * k * s;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * s);
                p.setColor(Ui.TEXT);
                c.drawRect(Math.max(dst.left, fx), Math.max(dst.top, fy),
                        Math.min(dst.right, fx + v.optInt(2) * k * s), Math.min(dst.bottom, fy + v.optInt(3) * k * s), p);
                p.setStyle(Paint.Style.FILL);
            }
            hits.add(new Hit(dst, "minimap"));
        }

        // heroes
        RectF hp = r(648, 192, W - M, 520);
        Ui.panel(c, hp, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, p);
        text(c, t("HEROES", "HELDEN"), 668, 222, label(12, Ui.TEXT_3));
        JSONArray heroes = state.optJSONArray("heroes");
        int nh = heroes == null ? 0 : Math.min(heroes.length(), 5);
        for (int i = 0; i < nh; i++) {
            JSONObject h = heroes.optJSONObject(i);
            float y = 236 + i * 56;
            RectF row = r(660, y, W - M - 12, y + 50);
            boolean sel = h.optBoolean("selected");
            Ui.panel(c, row, 8 * s, sel ? Ui.PANEL_2 : Ui.PANEL, sel ? Ui.AMBER : Ui.LINE, p);
            Paint nm = font(Ui.semi(), 20, h.optBoolean("sleeping") ? Ui.TEXT_3 : Ui.TEXT);
            text(c, Ui.fit(h.optString("name"), nm, 200 * s), 676, y + 32, nm);
            text(c, (de ? "ST " : "LV ") + h.optInt("level"), 890, y + 31, label(12, Ui.TEXT_2));
            float bx = 960, bw = W - M - 30 - bx;
            Ui.bar(c, r(bx, y + 14, bx + bw, y + 20), h.optInt("mp") / (float) Math.max(1, h.optInt("mpMax")), Ui.AMBER, Ui.AMBER_2, p);
            Ui.bar(c, r(bx, y + 30, bx + bw, y + 36), h.optInt("mana") / (float) Math.max(1, h.optInt("manaMax")), Ui.BLUE, Ui.BLUE_2, p);
            hits.add(new Hit(row, "hero " + h.optInt("id")));
        }
        if (nh == 0) {
            text(c, t("No heroes", "Keine Helden"), 676, 270, font(Ui.inter(), 18, Ui.TEXT_3));
        }

        // towns
        RectF tp = r(648, 536, W - M, 800);
        Ui.panel(c, tp, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, p);
        text(c, t("TOWNS · TAP AGAIN TO OPEN", "STÄDTE · NOCHMAL TIPPEN = ÖFFNEN"), 668, 566, label(12, Ui.TEXT_3));
        JSONArray towns = state.optJSONArray("towns");
        int nt = towns == null ? 0 : Math.min(towns.length(), 4);
        for (int i = 0; i < nt; i++) {
            JSONObject tw2 = towns.optJSONObject(i);
            float y = 580 + i * 52;
            RectF row = r(660, y, W - M - 12, y + 46);
            boolean sel = tw2.optBoolean("selected");
            Ui.panel(c, row, 8 * s, sel ? Ui.PANEL_2 : Ui.PANEL, sel ? Ui.AMBER : Ui.LINE, p);
            Paint nm = font(Ui.semi(), 20, Ui.TEXT);
            text(c, Ui.fit(tw2.optString("name"), nm, 330 * s), 676, y + 30, nm);
            if (tw2.optBoolean("built")) {
                Paint b = label(11, Ui.GREEN);
                b.setTextAlign(Paint.Align.RIGHT);
                text(c, t("BUILT TODAY", "HEUTE GEBAUT"), W - M - 28, y + 29, b);
            }
            hits.add(new Hit(row, "town " + tw2.optInt("id")));
        }
        if (nt == 0) {
            text(c, t("No towns", "Keine Städte"), 676, 610, font(Ui.inter(), 18, Ui.TEXT_3));
        }

        // army of the selection
        text(c, t("ARMY", "ARMEE") + (state.optString("armyOf").isEmpty() ? "" : " · " + state.optString("armyOf").toUpperCase(Locale.ROOT)),
                M, 836, label(12, Ui.TEXT_3));
        JSONArray army = state.optJSONArray("army");
        float aw = (W - 2 * M - 6 * 10) / 7f;
        for (int i = 0; i < 7; i++) {
            float x = M + i * (aw + 10);
            Ui.panel(c, r(x, 848, x + aw, 924), 10 * s, Ui.PANEL, Ui.LINE, p);
            JSONObject st = null;
            for (int k = 0; army != null && k < army.length(); k++) {
                if (army.optJSONObject(k).optInt("slot") == i) st = army.optJSONObject(k);
            }
            if (st != null) {
                text(c, String.valueOf(st.optInt("count")), x + 12, 882, font(Ui.mono(), 24, Ui.TEXT));
                Paint n = font(Ui.inter(), 14, Ui.TEXT_2);
                text(c, Ui.fit(st.optString("name"), n, (aw - 24) * s), x + 12, 910, n);
            }
        }

        // buttons
        boolean sleeping = false;
        for (int i = 0; heroes != null && i < heroes.length(); i++) {
            if (heroes.optJSONObject(i).optBoolean("selected")) sleeping = heroes.optJSONObject(i).optBoolean("sleeping");
        }
        String[][] b = {
                {t("END TURN", "ZUG BEENDEN"), "key adventureEndTurn"},
                {t("SPELLBOOK", "ZAUBERBUCH"), "key adventureCastSpell"},
                {sleeping ? t("WAKE", "WECKEN") : t("SLEEP", "SCHLAFEN"), "key adventureToggleSleep"},
                {t("NEXT HERO", "NÄCHSTER HELD"), "key adventureNextHero"},
                {t("GAME MENU", "SPIELMENÜ"), "key globalOptions"},
                {t("SAVE", "SPEICHERN"), "key adventureSaveGame"},
        };
        buttons(c, b, 0);
    }

    private void buttons(Canvas c, String[][] b, int primary) {
        float bw = (W - 2 * M - (b.length - 1) * 10) / b.length;
        for (int i = 0; i < b.length; i++) {
            float x = M + i * (bw + 10);
            RectF br = r(x, 944, x + bw, 1052);
            boolean main = i == primary;
            Ui.panel(c, br, Ui.RADIUS * s, main ? Ui.AMBER : Ui.PANEL_2, main ? Ui.AMBER_2 : Ui.LINE_2, p);
            Paint l = label(14, main ? Ui.BG : Ui.TEXT);
            l.setTextAlign(Paint.Align.CENTER);
            text(c, b[i][0], x + bw / 2, 1004, l);
            hits.add(new Hit(br, b[i][1]));
        }
    }

    private void drawBattle(Canvas c, JSONObject b) {
        if (b == null) b = new JSONObject();
        JSONObject u = b.optJSONObject("unit");
        RectF up = r(M, 100, 628, 560);
        Ui.panel(c, up, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, p);
        text(c, t("ACTIVE UNIT", "AKTIVE EINHEIT"), M + 20, 130, label(12, Ui.TEXT_3));
        if (u != null) {
            Paint nm = font(Ui.bold(), 34, Ui.TEXT);
            text(c, Ui.fit(u.optString("name"), nm, 420 * s), M + 20, 182, nm);
            Paint cnt = font(Ui.mono(), 34, Ui.AMBER_2);
            cnt.setTextAlign(Paint.Align.RIGHT);
            text(c, "×" + u.optInt("count"), 608, 182, cnt);
            String[][] rows = {
                    {t("ATTACK", "ANGRIFF"), String.valueOf(u.optInt("attack"))},
                    {t("DEFENSE", "VERTEIDIGUNG"), String.valueOf(u.optInt("defense"))},
                    {t("DAMAGE", "SCHADEN"), u.optInt("dmgMin") + "–" + u.optInt("dmgMax")},
                    {t("HEALTH", "LEBEN"), u.optInt("hp") + " / " + u.optInt("hpMax")},
                    {t("SPEED", "GESCHWINDIGKEIT"), String.valueOf(u.optInt("speed"))},
                    {t("SHOTS", "SCHÜSSE"), u.optBoolean("shooter") ? String.valueOf(u.optInt("shots")) : "–"},
            };
            for (int i = 0; i < rows.length; i++) {
                float y = 240 + i * 52;
                text(c, rows[i][0], M + 20, y, label(13, Ui.TEXT_2));
                Paint v = font(Ui.mono(), 24, Ui.TEXT);
                v.setTextAlign(Paint.Align.RIGHT);
                text(c, rows[i][1], 608, y, v);
                p.setColor(Ui.LINE);
                c.drawRect(r(M + 20, y + 16, 608, y + 17), p);
            }
            Ui.bar(c, r(M + 20, 536, 608, 542), u.optInt("hp") / (float) Math.max(1, u.optInt("hpMax")), Ui.RED, 0xFFF08A6E, p);
        } else {
            text(c, t("Waiting for your turn …", "Warte auf Euren Zug …"), M + 20, 190, font(Ui.inter(), 22, Ui.TEXT_3));
        }

        RectF hp = r(648, 100, W - M, 300);
        Ui.panel(c, hp, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, p);
        text(c, t("HERO", "HELD"), 668, 130, label(12, Ui.TEXT_3));
        if (b.has("hero")) {
            Paint nm = font(Ui.semi(), 28, Ui.TEXT);
            text(c, Ui.fit(b.optString("hero"), nm, 520 * s), 668, 178, nm);
            text(c, t("SPELL POINTS", "ZAUBERPUNKTE"), 668, 232, label(12, Ui.TEXT_2));
            Paint v = font(Ui.mono(), 22, Ui.BLUE_2);
            v.setTextAlign(Paint.Align.RIGHT);
            text(c, b.optInt("mana") + " / " + b.optInt("manaMax"), W - M - 20, 232, v);
            Ui.bar(c, r(668, 254, W - M - 20, 262), b.optInt("mana") / (float) Math.max(1, b.optInt("manaMax")), Ui.BLUE, Ui.BLUE_2, p);
        } else {
            text(c, t("No hero in this battle", "Kein Held in diesem Kampf"), 668, 180, font(Ui.inter(), 20, Ui.TEXT_3));
        }

        RectF lp = r(648, 316, W - M, 924);
        Ui.panel(c, lp, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, p);
        text(c, t("BATTLE LOG", "KAMPFLOG"), 668, 346, label(12, Ui.TEXT_3));
        JSONArray log = b.optJSONArray("log");
        Paint lt = font(Ui.inter(), 18, Ui.TEXT_2);
        float y = 900;
        for (int i = log == null ? -1 : log.length() - 1; i >= 0 && y > 370; i--) {
            List<String> lines = wrap(clean(log.optString(i)), lt, (W - M - 690) * s);
            for (int k = lines.size() - 1; k >= 0 && y > 370; k--) {
                lt.setColor(i == log.length() - 1 ? Ui.TEXT : Ui.TEXT_2);
                text(c, lines.get(k), 668, y, lt);
                y -= 26;
            }
            y -= 10;
        }

        RectF tip = r(M, 580, 628, 924);
        Ui.panel(c, tip, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, p);
        text(c, t("CONTROLS", "STEUERUNG"), M + 20, 610, label(12, Ui.TEXT_3));
        Paint h = font(Ui.inter(), 19, Ui.TEXT_2);
        String[] help = de
                ? new String[]{"Oben tippen: ziehen / angreifen", "A: bestätigen · B: abbrechen", "Rechts halten: Info zur Einheit",
                "Warten: Einheit später ziehen", "Verteidigen: +Verteidigung bis zum nächsten Zug"}
                : new String[]{"Tap the top screen to move / attack", "A: confirm · B: cancel", "Hold: unit info",
                "Wait: act later this round", "Defend: bonus defense until next turn"};
        for (int i = 0; i < help.length; i++) {
            text(c, help[i], M + 20, 652 + i * 40, h);
        }

        String[][] bt = {
                {t("SPELLBOOK", "ZAUBERBUCH"), "key battleCastSpell"},
                {t("WAIT", "WARTEN"), "key battleWait"},
                {t("DEFEND", "VERTEIDIGEN"), "key battleDefend"},
                {t("AUTO", "AUTO-KAMPF"), "key battleAutocombat"},
                {t("RETREAT", "RÜCKZUG"), "key battleRetreat"},
                {t("SURRENDER", "AUFGEBEN"), "key battleSurrender"},
        };
        buttons(c, bt, 0);
    }

    /** VCMI text markup: {highlight} and {#color|text} -> plain text. */
    private static String clean(String str) {
        return str.replaceAll("\\{#[0-9A-Za-z]+\\|", "").replace("{", "").replace("}", "");
    }

    private static List<String> wrap(String str, Paint paint, float width) {
        List<String> out = new ArrayList<>();
        for (String para : str.split("\n")) {
            String line = "";
            for (String w : para.split(" ")) {
                String cand = line.isEmpty() ? w : line + " " + w;
                if (paint.measureText(cand) > width && !line.isEmpty()) {
                    out.add(line);
                    line = w;
                } else {
                    line = cand;
                }
            }
            if (!line.isEmpty()) out.add(line);
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
        if (e.getActionMasked() == MotionEvent.ACTION_UP && pressed != null && pressed.r.contains(e.getX(), e.getY())) {
            if (pressed.cmd.equals("minimap")) {
                JSONObject map = state.optJSONObject("map");
                if (map != null) {
                    int tx = (int) ((e.getX() - pressed.r.left) / pressed.r.width() * map.optInt("w"));
                    int ty = (int) ((e.getY() - pressed.r.top) / pressed.r.height() * map.optInt("h"));
                    ThorBridge.send("tile " + tx + " " + ty);
                }
            } else {
                ThorBridge.send(pressed.cmd);
            }
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
