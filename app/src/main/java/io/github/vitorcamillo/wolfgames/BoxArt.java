package io.github.vitorcamillo.wolfgames;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Arte de caixa gerada a partir da capa (lombada, verso, disco e bandeja).
 * O catálogo traz uma única capa por jogo; o resto é desenhado aqui, em estilo neutro.
 * Só android.graphics; nenhum recurso novo em res/.
 */
final class BoxArt {
    private static final int DEFAULT_TINT = 0xff2a6f5a;
    private static final int MAX_NAME = 120;
    private static final String ELLIPSIS = "…";

    private BoxArt() {}

    /** Cor média saturada da capa; null ou sem pixels válidos retorna 0xff2a6f5a. */
    static int tint(Bitmap cover) {
        if (!usable(cover)) return DEFAULT_TINT;
        Bitmap small = Bitmap.createScaledBitmap(cover, 16, 16, true);
        int[] px = new int[256];
        small.getPixels(px, 0, 16, 0, 0, 16, 16);
        if (small != cover) small.recycle();

        float[] hsv = new float[3];
        long r = 0, g = 0, b = 0;
        int n = 0;
        long r2 = 0, g2 = 0, b2 = 0;
        int n2 = 0;
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            float s = hsv[1];
            float v = hsv[2];
            if (v < 0.12f || v > 0.94f) continue; // quase preto ou quase branco
            if (s >= 0.45f && v >= 0.35f && v <= 0.7f) {
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                n++;
            }
            r2 += Color.red(p);
            g2 += Color.green(p);
            b2 += Color.blue(p);
            n2++;
        }
        if (n == 0) {
            r = r2;
            g = g2;
            b = b2;
            n = n2;
        }
        if (n == 0) return DEFAULT_TINT;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    /** Capa substituta: gradiente diagonal escurecido, nome centralizado em até 4 linhas. */
    static Bitmap placeholder(String name, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w, h,
                scale(DEFAULT_TINT, 0.85f), scale(DEFAULT_TINT, 0.42f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Paint tp = text(Math.max(8f, h * 0.075f), Color.WHITE, false);
        drawLines(c, fitLines(name, tp, w * 0.86f, 4), tp, w / 2f, h / 2f);
        return bmp;
    }

    /** Lombada w x h: fundo fixo, faixa superior com "PC", nome vertical lendo de cima para baixo. */
    static Bitmap spine(int tint, String name, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawRect(0, 0, w, h, fillPaint(0xff1b1f27));

        float band = h * 0.09f;
        c.drawRect(0, 0, w, band, fillPaint(0xff11141a));
        Paint pc = text(Math.max(6f, w * 0.30f), 0xff8fb4d9, true);
        drawLines(c, oneLine("PC"), pc, w / 2f, band / 2f);

        String n = clean(name);
        if (!n.isEmpty()) {
            float avail = (h - band) * 0.88f;
            Paint tp = text(Math.max(6f, w * 0.46f), 0xffdfe8f3, false);
            tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            String line = fitOneLine(n, tp, avail);
            c.save();
            c.translate(w / 2f, band + (h - band) / 2f);
            c.rotate(90f);
            drawLines(c, oneLine(line), tp, 0, 0);
            c.restore();
        }
        return bmp;
    }

    /** Verso w x h: capa em desfoque, escurecida, capa nítida no centro e faixa inferior. */
    static Bitmap back(Bitmap cover, int tint, String name, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        int base = opaque(tint);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        if (usable(cover)) {
            int sh = Math.max(1, Math.round(24f * cover.getHeight() / cover.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(cover, 24, sh, true);
            Bitmap blur = Bitmap.createScaledBitmap(small, w, h, true);
            c.drawBitmap(blur, null, new RectF(0, 0, w, h), bitmapPaint);
            if (blur != small) blur.recycle();
            if (small != cover) small.recycle();
        } else {
            c.drawRect(0, 0, w, h, fillPaint(base));
        }
        // escurece para ~45% do brilho
        c.drawRect(0, 0, w, h, fillPaint(0x8c000000));

        Paint tp = text(Math.max(8f, h * 0.05f), Color.WHITE, true);
        drawLines(c, fitLines(name, tp, w * 0.8f, 2), tp, w / 2f, h * 0.125f);

        float bh = h * 0.5f;
        float bw = Math.min(w * 0.9f, bh * 0.7f);
        RectF box = new RectF((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f);
        float rr = Math.min(bw, bh) * 0.06f;
        Paint face = usable(cover)
                ? centerCropPaint(cover, box.left, box.top, bw, bh)
                : fillPaint(base);
        c.drawRoundRect(box, rr, rr, face);

        float bandTop = h * 0.91f;
        c.drawRect(0, bandTop, w, h, fillPaint(0x5a000000));
        Paint bp = text(Math.max(6f, (h - bandTop) * 0.42f), Color.WHITE, false);
        String footer = fitOneLine("PC  ·  Biblioteca Wolf", bp, w * 0.9f);
        drawLines(c, oneLine(footer), bp, w / 2f, bandTop + (h - bandTop) / 2f);
        return bmp;
    }

    /** Disco size x size: capa circular, furo central, anel de plástico, cubo metálico e brilho em varredura. */
    static Bitmap disc(Bitmap cover, int tint, int size) {
        size = Math.max(1, size);
        float r = size / 2f;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint face = usable(cover)
                ? centerCropPaint(cover, 0, 0, size, size)
                : fillPaint(opaque(tint));
        c.drawCircle(r, r, r, face);

        // reflexo em varredura com duas faixas opostas
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new SweepGradient(r, r,
                new int[]{0x00ffffff, 0x3cffffff, 0x00ffffff, 0x00ffffff, 0x3cffffff, 0x00ffffff},
                new float[]{0f, 0.14f, 0.28f, 0.5f, 0.64f, 0.78f}));
        c.drawCircle(r, r, r, sheen);

        // cubo metálico translúcido, de 0.11 a 0.22, com linha fina em 0.23
        c.drawCircle(r, r, size * 0.22f, fillPaint(0x40ffffff));
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, size * 0.008f));
        line.setColor(0x40ffffff);
        c.drawCircle(r, r, size * 0.23f, line);

        // anel de plástico translúcido cinza claro, de 0.075 a 0.11
        c.drawCircle(r, r, size * 0.11f, fillPaint(0xb4d8dce2));
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawCircle(r, r, size * 0.075f, hole);

        // borda externa clara e translúcida, 0.015 do tamanho
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(size * 0.015f);
        edge.setColor(0x66ffffff);
        c.drawCircle(r, r, r - size * 0.0075f, edge);
        return bmp;
    }

    /** Interior da bandeja: plástico escuro fixo, cavidade mais escura e cubo central com dentes e furo. */
    static Bitmap tray(int tint, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawRect(0, 0, w, h, fillPaint(0xff11141a));

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        Paint cavity = new Paint(Paint.ANTI_ALIAS_FLAG);
        cavity.setShader(new RadialGradient(cx, cy, w * 0.42f,
                0xff0d1015, 0xff171b23, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, w * 0.42f, cavity);

        // cubo central cinza com 10 dentes curtos ao redor
        Paint hub = fillPaint(0xff2a2f38);
        float hr = w * 0.14f;
        float tl = w * 0.035f;
        float tw = w * 0.030f;
        for (int i = 0; i < 10; i++) {
            c.save();
            c.rotate(i * 36f, cx, cy);
            c.drawRect(cx - tw / 2f, cy - hr - tl, cx + tw / 2f, cy - hr, hub);
            c.restore();
        }
        c.drawCircle(cx, cy, hr, hub);

        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawCircle(cx, cy, w * 0.05f, hole);

        // pequeno entalhe semicircular na borda esquerda, em 0.5h
        c.drawCircle(0, cy, w * 0.07f, fillPaint(0xff070a0e));
        return bmp;
    }

    // ---- auxiliares ----

    private static boolean usable(Bitmap b) {
        return b != null && !b.isRecycled() && b.getWidth() > 0 && b.getHeight() > 0;
    }

    private static int opaque(int c) {
        return 0xff000000 | c;
    }

    /** Multiplica RGB por f (com limite 0..255), resultado opaco. */
    private static int scale(int c, float f) {
        return Color.rgb(clamp255(Color.red(c) * f), clamp255(Color.green(c) * f), clamp255(Color.blue(c) * f));
    }

    private static int clamp255(float v) {
        return Math.round(Math.min(255f, Math.max(0f, v)));
    }

    private static Paint fillPaint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    private static Paint text(float size, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    /** Preenche o retângulo (left, top, dw x dh) com a capa em center-crop. */
    private static Paint centerCropPaint(Bitmap src, float left, float top, float dw, float dh) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float s = Math.max(dw / src.getWidth(), dh / src.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate(left + (dw - src.getWidth() * s) / 2f, top + (dh - src.getHeight() * s) / 2f);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        return p;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
        return t.trim();
    }

    private static List<String> oneLine(String s) {
        return Collections.singletonList(s);
    }

    /** Desenha as linhas centradas em (cx, cy); a baseline sai das métricas da fonte. */
    private static void drawLines(Canvas c, List<String> lines, Paint p, float cx, float cy) {
        if (lines.isEmpty()) return;
        Paint.FontMetrics fm = p.getFontMetrics();
        float lh = fm.bottom - fm.top;
        float y = cy - lh * lines.size() / 2f - fm.top;
        for (String s : lines) {
            c.drawText(s, cx, y, p);
            y += lh;
        }
    }

    /** Quebra o texto por palavras (e por caractere, se uma palavra não couber). */
    private static List<String> wrap(String text, Paint p, float maxW) {
        List<String> lines = new ArrayList<>();
        String t = clean(text);
        if (t.isEmpty()) return lines;
        StringBuilder cur = new StringBuilder();
        for (String word : t.split("\\s+")) {
            String joined = cur.length() == 0 ? word : cur + " " + word;
            if (p.measureText(joined) <= maxW) {
                cur.setLength(0);
                cur.append(joined);
                continue;
            }
            if (cur.length() > 0) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            StringBuilder piece = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                char ch = word.charAt(i);
                if (piece.length() > 0 && p.measureText(piece.toString() + ch) > maxW) {
                    lines.add(piece.toString());
                    piece.setLength(0);
                }
                piece.append(ch);
            }
            cur.append(piece);
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    /** Até maxLines linhas; o excedente vai para a última linha, com reticências. */
    private static List<String> fitLines(String text, Paint p, float maxW, int maxLines) {
        List<String> lines = wrap(text, p, maxW);
        if (lines.size() <= maxLines) return lines;
        StringBuilder rest = new StringBuilder();
        for (int i = maxLines - 1; i < lines.size(); i++) {
            rest.append(lines.get(i)).append(' ');
        }
        List<String> out = new ArrayList<>(lines.subList(0, maxLines - 1));
        out.add(fitOneLine(rest.toString().trim(), p, maxW));
        return out;
    }

    /** Corta s para caber em maxW, com reticências quando não cabe. */
    private static String fitOneLine(String s, Paint p, float maxW) {
        if (s == null || s.isEmpty()) return "";
        if (p.measureText(s) <= maxW) return s;
        float e = p.measureText(ELLIPSIS);
        int n = s.length();
        while (n > 0 && p.measureText(s, 0, n) + e > maxW) n--;
        return s.substring(0, n).trim() + ELLIPSIS;
    }
}
