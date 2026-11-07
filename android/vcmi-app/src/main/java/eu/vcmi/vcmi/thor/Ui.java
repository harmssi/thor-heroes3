package eu.vcmi.vcmi.thor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import eu.vcmi.vcmi.R;

/**
 * Thor Edition design tokens (shared look of the Thor ports): near-black surfaces, quiet panels
 * with 1-px lines, Inter for UI, UPPERCASE micro labels with wide tracking, JetBrains Mono for
 * numbers, thin gradient bars. Amber is the brand accent. Fonts: SIL OFL 1.1 (res/raw/*_ofl.txt).
 */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF08090B;
    static final int PANEL = 0xFF121418;
    static final int PANEL_2 = 0xFF181B20;
    static final int LINE = 0xFF252931;
    static final int LINE_2 = 0xFF323844;
    static final int TEXT = 0xFFF2F2EF;
    static final int TEXT_2 = 0xFFA7ABB3;
    static final int TEXT_3 = 0xFF6A707B;
    static final int AMBER = 0xFFE8B04A;
    static final int AMBER_2 = 0xFFF3D27E;
    static final int RED = 0xFFE5484D;
    static final int BLUE = 0xFF4C9EEB;
    static final int BLUE_2 = 0xFF9CCBF5;
    static final int GREEN = 0xFF4CC38A;

    static final float TRACKING = 0.28f;
    static final float RADIUS = 12f;

    private static Typeface inter, semi, bold, mono;

    static void init(Context ctx) {
        if (inter != null) {
            return;
        }
        inter = font(ctx, R.font.inter_regular, Typeface.SANS_SERIF);
        semi = font(ctx, R.font.inter_semibold, inter);
        bold = font(ctx, R.font.inter_bold, inter);
        mono = font(ctx, R.font.mono_bold, Typeface.MONOSPACE);
    }

    private static Typeface font(Context ctx, int id, Typeface fallback) {
        try {
            return ctx.getResources().getFont(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static Paint label(float size, int color) {
        Paint p = text(semi, size, color);
        p.setLetterSpacing(TRACKING);
        return p;
    }

    static Paint text(Typeface tf, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    static Typeface inter() { return inter; }
    static Typeface semi() { return semi; }
    static Typeface bold() { return bold; }
    static Typeface mono() { return mono; }

    static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(a * 255) << 24);
    }

    /** Filled rounded rect with a 1-px line. */
    static void panel(Canvas c, RectF r, float radius, int fill, int line, Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f);
        p.setColor(line);
        c.drawRoundRect(new RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f), radius, radius, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Thin gradient bar (track + fill). */
    static void bar(Canvas c, RectF r, float fraction, int from, int to, Paint p) {
        float rad = r.height() / 2f;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(LINE);
        c.drawRoundRect(r, rad, rad, p);
        fraction = Math.max(0f, Math.min(1f, fraction));
        if (fraction > 0f) {
            p.setShader(new LinearGradient(r.left, 0, r.right, 0, from, to, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(r.left, r.top, r.left + r.width() * fraction, r.bottom), rad, rad, p);
            p.setShader(null);
        }
    }

    /** Own emblem: hexagon frame with a stacked chevron (no game artwork). */
    static void emblem(Canvas c, float cx, float cy, float r, int color, Paint p) {
        android.graphics.Path hex = new android.graphics.Path();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i - 90);
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            if (i == 0) hex.moveTo(x, y); else hex.lineTo(x, y);
        }
        hex.close();
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(alpha(color, 0.55f));
        c.drawPath(hex, p);
        p.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 2; k++) {
            float oy = cy - r * 0.22f + k * r * 0.42f;
            android.graphics.Path ch = new android.graphics.Path();
            ch.moveTo(cx - r * 0.55f, oy);
            ch.lineTo(cx, oy + r * 0.34f);
            ch.lineTo(cx + r * 0.55f, oy);
            ch.lineTo(cx + r * 0.55f, oy + r * 0.17f);
            ch.lineTo(cx, oy + r * 0.51f);
            ch.lineTo(cx - r * 0.55f, oy + r * 0.17f);
            ch.close();
            p.setColor(k == 0 ? color : alpha(color, 0.55f));
            c.drawPath(ch, p);
        }
    }

    /** Text cut to a width with an ellipsis. */
    static String fit(String s, Paint p, float w) {
        if (p.measureText(s) <= w) return s;
        int n = p.breakText(s, true, w - p.measureText("…"), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }
}
