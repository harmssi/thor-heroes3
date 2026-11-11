package eu.vcmi.vcmi.thor;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The look of the deck: Heroes III's own interface art and fonts. Nothing is shipped with the app:
 * every picture is asked from the running game ("art <key>", see client/thor/ThorDeck.cpp), which
 * reads it from the player's own game files. Until a piece arrives - or if the files do not have
 * it - the deck draws that piece itself in the same style (stone brown, gold bevels, serif type).
 */
final class H3Art {
    private H3Art() {}

    static final int GOLD = 0xFFE5D77B;      // the game's yellow text
    static final int WHITE = 0xFFFFF3DE;     // the game's white text
    static final int GREY = 0xFF8C8273;
    static final int GREEN = 0xFF6BD24A;
    static final int RED = 0xFFE8503C;
    static final int FRAME_HI = 0xFFE7C66B;
    static final int FRAME_MID = 0xFFA57B29;
    static final int FRAME_LO = 0xFF5A3F10;
    static final int STONE = 0xFF3B2A1C;
    static final int STONE_DARK = 0xFF1E150D;

    static final class Font {
        Bitmap atlas;
        int[] x = new int[224];
        int[] w = new int[224];
    }

    private static final Map<String, Bitmap> images = new HashMap<>();
    private static final Map<String, Font> fonts = new HashMap<>();
    private static final Set<String> asked = new HashSet<>();
    private static final Paint tile = new Paint();
    private static final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint glyph = new Paint();
    private static final Map<Integer, PorterDuffColorFilter> tints = new HashMap<>();
    private static Bitmap tileSource;

    /** Requests that were sent while the game was not listening are asked again. */
    static void retry() {
        asked.clear();
    }

    /** A picture from the game files, or null while it is not there (asked once). */
    static Bitmap image(String key) {
        Bitmap b = images.get(key);
        if (b == null && asked.add(key)) {
            ThorBridge.send("art " + key);
        }
        return b;
    }

    static Font font(String name) {
        Font f = fonts.get(name);
        if (f == null && asked.add("font:" + name)) {
            ThorBridge.send("art font:" + name);
        }
        return f;
    }

    static void put(String key, int w, int h, byte[] rgba, int[] meta) {
        if (w <= 0 || h <= 0 || rgba == null || rgba.length < w * h * 4) {
            return; // stays "asked": the deck keeps drawing this piece itself
        }
        for (int i = 0; i < w * h * 4; i += 4) { // Android bitmaps are premultiplied
            int a = rgba[i + 3] & 0xFF;
            if (a != 255) {
                rgba[i] = (byte) ((rgba[i] & 0xFF) * a / 255);
                rgba[i + 1] = (byte) ((rgba[i + 1] & 0xFF) * a / 255);
                rgba[i + 2] = (byte) ((rgba[i + 2] & 0xFF) * a / 255);
            }
        }
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(ByteBuffer.wrap(rgba, 0, w * h * 4));
        if (key.startsWith("font:")) {
            if (meta == null || meta.length < 224) {
                return;
            }
            Font f = new Font();
            f.atlas = b;
            int x = 0;
            for (int i = 0; i < 224; i++) {
                f.x[i] = x;
                f.w[i] = meta[i];
                x += meta[i] + 4;
            }
            fonts.put(key.substring(5), f);
        } else {
            images.put(key, b);
        }
    }

    // ---------------------------------------------------------------- text

    /** Only what the game's fonts have (Latin-1); typographic characters become plain ones. */
    private static String plain(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '…') out.append("...");
            else if (ch == '–' || ch == '—' || ch == '·') out.append('-');
            else if (ch == '×') out.append('x');
            else if (ch == '’' || ch == '‘') out.append('\'');
            else if (ch == '“' || ch == '”' || ch == '„') out.append('"');
            else if (ch < 32 || ch > 255 || (ch >= 127 && ch < 160)) out.append(' ');
            else out.append(ch);
        }
        return out.toString();
    }

    private static Paint serif(float lineH, int color) {
        ink.setShader(null);
        ink.setStyle(Paint.Style.FILL);
        ink.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        ink.setTextSize(lineH * 0.72f);
        ink.setColor(color);
        ink.setShadowLayer(1.5f, 1.5f, 1.5f, 0xFF000000);
        return ink;
    }

    static float width(String font, String s, float lineH) {
        Font f = font(font);
        s = plain(s);
        if (f == null) {
            float w = serif(lineH, WHITE).measureText(s);
            ink.clearShadowLayer();
            return w;
        }
        float k = lineH / f.atlas.getHeight(), w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += f.w[s.charAt(i) - 32] * k;
        }
        return w;
    }

    static String fit(String font, String s, float lineH, float max) {
        if (width(font, s, lineH) <= max) return s;
        while (s.length() > 1 && width(font, s + "..", lineH) > max) {
            s = s.substring(0, s.length() - 1);
        }
        return s.trim() + "..";
    }

    /** One line in a game font; align 0 = left, 1 = centred, 2 = right; y = top of the line. */
    static void text(Canvas c, String font, String s, float x, float y, float lineH, int color, int align) {
        Font f = font(font);
        s = plain(s);
        float w = width(font, s, lineH);
        x -= align == 1 ? w / 2 : align == 2 ? w : 0;
        if (f == null) {
            c.drawText(s, x, y + lineH * 0.76f, serif(lineH, color));
            ink.clearShadowLayer();
            return;
        }
        float k = lineH / f.atlas.getHeight();
        PorterDuffColorFilter tint = tints.get(color);
        if (tint == null) {
            tint = new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY);
            tints.put(color, tint);
        }
        glyph.setColorFilter(tint);
        glyph.setFilterBitmap(Math.abs(k - Math.round(k)) > 0.02f); // whole multiples stay crisp pixels
        Rect src = new Rect();
        RectF dst = new RectF();
        for (int i = 0; i < s.length(); i++) {
            int g = s.charAt(i) - 32;
            if (f.w[g] > 0) {
                src.set(f.x[g], 0, f.x[g] + f.w[g] + 4, f.atlas.getHeight());
                dst.set(x, y, x + (f.w[g] + 4) * k, y + lineH);
                c.drawBitmap(f.atlas, src, dst, glyph);
            }
            x += f.w[g] * k;
        }
    }

    // ---------------------------------------------------------------- surfaces

    /** The game's dialog stone texture (DiBoxBck), tiled; own brown gradient until it arrives. */
    static void stone(Canvas c, RectF r, float scale) {
        Bitmap b = image("img:DIBOXBCK");
        ink.clearShadowLayer();
        ink.setStyle(Paint.Style.FILL);
        if (b == null) {
            ink.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, STONE, STONE_DARK, Shader.TileMode.CLAMP));
            c.drawRect(r, ink);
            ink.setShader(null);
            return;
        }
        if (tileSource != b) {
            tileSource = b;
            tile.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }
        Matrix m = new Matrix();
        m.setScale(2 * scale, 2 * scale);
        tile.getShader().setLocalMatrix(m);
        tile.setFilterBitmap(false);
        c.drawRect(r, tile);
    }

    /** Gold bevel frame around r (drawn outside-in), like the game's panel borders. */
    static void frame(Canvas c, RectF r, float u, boolean sunken) {
        ink.clearShadowLayer();
        ink.setShader(null);
        ink.setStyle(Paint.Style.FILL);
        int[] tl = sunken ? new int[]{0xFF000000, FRAME_LO, FRAME_MID} : new int[]{0xFF000000, FRAME_HI, FRAME_MID};
        int[] br = sunken ? new int[]{0xFF000000, FRAME_HI, FRAME_MID} : new int[]{0xFF000000, FRAME_LO, FRAME_MID};
        for (int i = 0; i < 3; i++) {
            float o = i * u;
            ink.setColor(tl[i]);
            c.drawRect(r.left + o, r.top + o, r.right - o, r.top + o + u, ink);
            c.drawRect(r.left + o, r.top + o, r.left + o + u, r.bottom - o, ink);
            ink.setColor(br[i]);
            c.drawRect(r.left + o, r.bottom - o - u, r.right - o, r.bottom - o, ink);
            c.drawRect(r.right - o - u, r.top + o, r.right - o, r.bottom - o, ink);
        }
    }

    /** Dark inset field with a gold frame: the game's info boxes and list slots. */
    static void well(Canvas c, RectF r, float u) {
        ink.clearShadowLayer();
        ink.setShader(null);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(0xB0000000);
        c.drawRect(r, ink);
        frame(c, r, u, true);
    }

    static void fill(Canvas c, RectF r, int color) {
        ink.clearShadowLayer();
        ink.setShader(null);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(color);
        c.drawRect(r, ink);
    }

    /** Text button in the game's style: brown stone face, gold bevel, gold lettering. */
    static void button(Canvas c, RectF r, String label, float u, boolean pressed, boolean enabled, boolean lit) {
        ink.clearShadowLayer();
        ink.setStyle(Paint.Style.FILL);
        ink.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                pressed ? 0xFF2A1B0E : lit ? 0xFF7A5524 : 0xFF5C3F1E, pressed ? 0xFF4A3218 : lit ? 0xFF4E3414 : 0xFF2E1E0E, Shader.TileMode.CLAMP));
        c.drawRect(r, ink);
        ink.setShader(null);
        frame(c, r, u, pressed);
        float lineH = Math.min(14 * u, r.height() * 0.5f);
        String s = fit("medium", label, lineH, r.width() - 10 * u);
        text(c, "medium", s, r.centerX(), r.centerY() - lineH / 2 + (pressed ? u : 0), lineH, enabled ? (lit ? WHITE : GOLD) : GREY, 1);
    }

    /** A picture scaled into r (kept in proportion, centred); false if the game files do not have it (yet). */
    static boolean picture(Canvas c, String key, RectF r, boolean crisp, boolean dim) {
        Bitmap b = image(key);
        if (b == null) {
            return false;
        }
        float k = Math.min(r.width() / b.getWidth(), r.height() / b.getHeight());
        float w = b.getWidth() * k, h = b.getHeight() * k;
        RectF dst = new RectF(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2);
        glyph.setColorFilter(dim ? dimFilter : null);
        glyph.setFilterBitmap(!crisp);
        c.drawBitmap(b, null, dst, glyph);
        return true;
    }

    private static final PorterDuffColorFilter dimFilter = new PorterDuffColorFilter(0xFF707070, PorterDuff.Mode.MULTIPLY);

    /** Vertical bar like the movement / spell point bars beside the game's hero portraits. */
    static void vbar(Canvas c, RectF r, float fraction, int from, int to) {
        fill(c, r, 0xFF000000);
        fraction = Math.max(0f, Math.min(1f, fraction));
        if (fraction > 0f) {
            ink.setShader(new LinearGradient(0, r.bottom, 0, r.top, from, to, Shader.TileMode.CLAMP));
            c.drawRect(r.left + 1, r.bottom - (r.height() - 2) * fraction - 1, r.right - 1, r.bottom - 1, ink);
            ink.setShader(null);
        }
    }

    static void hbar(Canvas c, RectF r, float fraction, int from, int to) {
        fill(c, r, 0xFF000000);
        fraction = Math.max(0f, Math.min(1f, fraction));
        if (fraction > 0f) {
            ink.setShader(new LinearGradient(r.left, 0, r.right, 0, from, to, Shader.TileMode.CLAMP));
            c.drawRect(r.left + 1, r.top + 1, r.left + 1 + (r.width() - 2) * fraction, r.bottom - 1, ink);
            ink.setShader(null);
        }
    }
}
