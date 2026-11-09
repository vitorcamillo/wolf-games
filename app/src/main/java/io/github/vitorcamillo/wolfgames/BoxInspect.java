package io.github.vitorcamillo.wolfgames;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Inspeção de uma caixa da prateleira: painel da fileira com a caixa fechada girável, aberta com
 * bandeja, tampa e disco. Só Canvas, sem OpenGL; draw() recebe o retângulo da fileira de jogos
 * (W, H), não a tela cheia. Os bitmaps são gerados em open(), na thread principal, e reciclados
 * em close()/release(). O caminho de desenho não aloca objetos: campos são reaproveitados.
 * A capa (cover) pertence ao chamador e nunca é reciclada aqui.
 */
final class BoxInspect {

    interface Host {
        void invalidateInspect();
        void inspectClosed();
        void inspectPlay();
    }

    private static final int CLOSED = 0, OPEN = 1, DISC_OUT = 2;
    private static final float DUR_POSE = 800f;
    private static final float DUR_SCALE = 650f;
    private static final float DUR_FADE = 450f;
    private static final long IDLE_MS = 2600L;
    private static final float DISC_SPIN = 25.7f;
    private static final float FRONT_ASPECT = 0.74f;
    /** Altura de referência das texturas: open() não conhece W/H; 512 px é o teto dos bitmaps. */
    private static final int TEX_H = 512;
    private static final int TEX_W = Math.round(FRONT_ASPECT * TEX_H);
    private static final int SPINE_W = Math.round(0.13f * TEX_H);
    private static final int DISC_PX = Math.min(512, Math.round(0.80f * TEX_H));
    private static final int CASE = 0xff1b1f27;
    private static final int SIDE = 0xff11141a;
    private static final int KEY_OK = 0xff4caf50;
    private static final int KEY_X = 0xff2196f3;
    private static final int KEY_BACK = 0xfff44336;
    private static final int KEY_ARROWS = 0x28ffffff;
    private static final String ELLIPSIS = "…";
    private static final String[] STATE_LABEL = {"INSPECIONAR", "CAIXA ABERTA", "DISCO FORA"};
    /** Pose alvo por estado, na convenção do BoxMath; sideDeg soma em rotY. */
    private static final float[] POSE_Y = {14f, -18f, 0f};
    private static final float[] POSE_X = {8f, 6f, 4f};
    /** A dica "OK Jogar" é trocada por playLabel em runtime. */
    private static final String[][] HINTS = {
            {"◄► Virar", "▲ Abrir caixa", "X Zoom", "OK Jogar", "Voltar"},
            {"▲ Tirar disco", "▼ Fechar", "X Zoom", "OK Jogar", "Voltar"},
            {"▼ Guardar disco", "X Zoom", "OK Jogar", "Voltar"}
    };

    private final Host host;
    private final float density;
    private final boolean animate;

    private boolean active;
    private int state = CLOSED;
    private boolean zoomed;
    private Bitmap cover;
    private int tint;
    private String name = "";
    private String playLabel = "OK Jogar";

    private Bitmap backBmp, spineBmp, trayBmp, discBmp, caseBmp, sideBmp;
    private final float[] cropSrc = new float[8];

    private long lastNow;
    private long lastDrawMs = -1;
    private long lastKeyMs = 0L;
    private float spinDeg;
    /** Alfa global da entrada (0..1), aplicado a todos os paints do quadro. */
    private float entryA = 1f;

    private final Anim side = new Anim(DUR_POSE);
    private final Anim poseY = new Anim(DUR_POSE);
    private final Anim poseX = new Anim(DUR_POSE);
    private final Anim opening = new Anim(DUR_POSE);
    private final Anim disc = new Anim(DUR_POSE);
    private final Anim scale = new Anim(DUR_SCALE);
    private final Anim fade = new Anim(DUR_FADE);

    // ---- scratch reaproveitado no desenho ----
    private final float[] pts = new float[12];
    private final float[] quad = new float[8];
    private final float[] quad2 = new float[8];
    private final float[] srcRect = new float[8];
    private final float[] faceQuads = new float[48];
    private final float[] faceZ = new float[6];
    private final boolean[] faceVis = new boolean[6];
    private final int[] order = new int[6];
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Path outlinePath = new Path();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint panelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private String fittedName;
    private int fittedW = -1, fittedH = -1;

    BoxInspect(Host host, float density, boolean animate) {
        this.host = host;
        this.density = density;
        this.animate = animate;
        panelFill.setColor(0x96000000); // preto alfa 150
        panelStroke.setStyle(Paint.Style.STROKE);
        panelStroke.setColor(0x1affffff); // branco alfa 26
        panelStroke.setStrokeWidth(Math.max(1f, density));
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(0x668fc3ff);
        outlinePaint.setStrokeWidth(1.5f * density);
        fillPaint.setColor(CASE);
        shadowPaint.setColor(0xff000000);
        textPaint.setColor(0xffffffff);
        textPaint.setTextAlign(Paint.Align.LEFT);
        keyPaint.setColor(0xffffffff);
        keyPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Abre a inspeção de uma caixa; gera os bitmaps desta abertura (thread principal). */
    void open(Bitmap cover, int tint, String name, boolean running) {
        releaseBitmaps();
        this.cover = cover;
        this.tint = tint;
        this.name = name == null ? "" : name.trim();
        this.playLabel = running ? "OK Continuar" : "OK Jogar";
        state = CLOSED;
        zoomed = false;
        active = true;
        spinDeg = 0f;
        lastDrawMs = -1;
        lastKeyMs = 0L;
        fittedName = null;
        fittedW = -1;
        fittedH = -1;

        side.snap(0f);
        poseY.snap(POSE_Y[CLOSED]);
        poseX.snap(POSE_X[CLOSED]);
        opening.snap(0f);
        disc.snap(0f);
        scale.snap(animate ? 0.84f : 1f);
        scale.set(1f);
        fade.snap(animate ? 0f : 1f);
        fade.set(1f);

        setCropSource();
        backBmp = BoxArt.back(cover, tint, this.name, TEX_W, TEX_H);
        spineBmp = BoxArt.spine(tint, this.name, SPINE_W, TEX_H);
        trayBmp = BoxArt.tray(tint, TEX_W, TEX_H);
        discBmp = BoxArt.disc(cover, tint, DISC_PX);
        caseBmp = solidBmp(CASE);
        sideBmp = solidBmp(SIDE);
        host.invalidateInspect();
    }

    /** Fecha a inspeção e recicla os bitmaps gerados. Idempotente. */
    void close() {
        boolean was = active;
        active = false;
        state = CLOSED;
        releaseBitmaps();
        if (was) host.invalidateInspect();
    }

    boolean active() {
        return active;
    }

    /** Trata uma tecla; retorna true se a inspeção a consumiu. */
    boolean key(int keyCode) {
        if (!active) return false;
        lastKeyMs = SystemClock.uptimeMillis();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (state == CLOSED) setState(OPEN);
                else if (state == OPEN) setState(DISC_OUT);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (state == DISC_OUT) setState(OPEN);
                else if (state == OPEN) setState(CLOSED);
                else {
                    close();
                    host.inspectClosed();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (state == CLOSED) {
                    float delta = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -180f : 180f;
                    side.set(side.to + delta);
                    host.invalidateInspect();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                host.inspectPlay();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                zoomed = !zoomed;
                host.invalidateInspect();
                return true;
            case KeyEvent.KEYCODE_BACK:
                close();
                host.inspectClosed();
                return true;
            default:
                return false;
        }
    }

    /** Desenha um quadro dentro do retângulo da fileira (width x height). Sem inspeção ativa, não faz nada. */
    void draw(Canvas canvas, int width, int height, long nowMs) {
        if (!active || canvas == null || width <= 0 || height <= 0) return;
        long now = nowMs;
        lastNow = now;
        side.begin(now);
        poseY.begin(now);
        poseX.begin(now);
        opening.begin(now);
        disc.begin(now);
        scale.begin(now);
        fade.begin(now);
        boolean busy = side.busy(now) || poseY.busy(now) || poseX.busy(now)
                || opening.busy(now) || disc.busy(now) || scale.busy(now);

        if (lastDrawMs < 0) lastKeyMs = now; // primeira moldura da abertura: recomeça a contagem ociosa
        float dt = lastDrawMs < 0 ? 0f : (float) Math.min(100L, now - lastDrawMs);
        if (animate && state == DISC_OUT) {
            spinDeg = (spinDeg + dt * DISC_SPIN / 1000f) % 360f;
        }
        lastDrawMs = now;

        float w0 = width, h0 = height;
        float cx = w0 / 2f, cy = 0.5f * h0;
        float sc = scale.valueAt(now) * (zoomed ? 1.12f : 1f);
        float h = (state == CLOSED ? 0.86f : 0.80f) * h0 * sc;
        float w = 0.667f * h;
        float d = 0.07f * w;
        float edge = 0.015f * w;
        float focal = 3.4f * h;

        float rotY = poseY.valueAt(now) + side.valueAt(now);
        float rotX = poseX.valueAt(now);
        if (animate && !busy && now - lastKeyMs > IDLE_MS) { // balanço ocioso só sem transição
            if (state != DISC_OUT) rotY += (float) Math.sin(now / 1700.0) * 9f;
            rotX += (float) Math.sin(now / 2300.0) * 3f;
        }

        entryA = fade.valueAt(now);
        applyAlpha(panelFill, 150);
        applyAlpha(panelStroke, 26);
        applyAlpha(bitmapPaint, 255);
        applyAlpha(fillPaint, 255);
        applyAlpha(outlinePaint, 0x66);

        // painel: retângulo arredondado na área inteira, como o painel do dock
        rect.set(0f, 0f, w0, h0);
        float rad = 0.08f * h0;
        canvas.drawRoundRect(rect, rad, rad, panelFill);
        canvas.drawRoundRect(rect, rad, rad, panelStroke);

        float op = opening.valueAt(now);
        if (op > 0.0005f) {
            drawOpen(canvas, w, h, d, edge, cx, cy, focal, rotY, rotX, op, now);
        } else {
            drawClosed(canvas, w, h, d, edge, cx, cy, focal, rotY, rotX);
        }
        drawLabels(canvas, w0, h0);

        if (animate) host.invalidateInspect(); // transição, giro e balanço pedem o próximo quadro
    }

    /** Libera tudo sem avisar o host. Idempotente. */
    void release() {
        active = false;
        state = CLOSED;
        releaseBitmaps();
    }

    // ---- estado ----

    private void setState(int next) {
        state = next;
        opening.set(next == CLOSED ? 0f : 1f);
        disc.set(next == DISC_OUT ? 1f : 0f);
        poseY.set(POSE_Y[next]);
        poseX.set(POSE_X[next]);
        host.invalidateInspect();
    }

    // ---- desenho ----

    private void drawClosed(Canvas c, float w, float h, float d, float edge, float cx, float cy,
                            float focal, float rotY, float rotX) {
        for (int f = 0; f < 6; f++) {
            BoxMath.faceCorners(f, w, h, d, pts);
            faceZ[f] = BoxMath.project(pts, 4, rotY, rotX, 0f, 0f, 0f, focal, cx, cy, quad);
            faceVis[f] = BoxMath.facing(quad);
            System.arraycopy(quad, 0, faceQuads, f * 8, 8);
        }
        // ordena faces visíveis da mais distante para a mais próxima (z maior = mais longe)
        int n = 0;
        for (int f = 0; f < 6; f++) {
            if (!faceVis[f]) continue;
            int j = n++;
            while (j > 0 && faceZ[order[j - 1]] < faceZ[f]) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = f;
        }
        for (int i = 0; i < n; i++) {
            int f = order[i];
            if (f == BoxMath.FRONT) {
                // moldura CASE com a capa inset de edge
                drawQuad(c, faceQuads, f * 8, fillPaint);
                setInsetFront(w, h, edge, -d / 2f);
                BoxMath.project(pts, 4, rotY, rotX, 0f, 0f, 0f, focal, cx, cy, quad2);
                if (cover != null) drawWithPoly(c, cover, cropSrc, quad2, 0, 0f);
            } else {
                drawFace(c, f, faceQuads, f * 8);
            }
        }
    }

    private void drawOpen(Canvas c, float w, float h, float d, float edge, float cx, float cy,
                          float focal, float rotY, float rotX, float op, long now) {
        float hw = w / 2f, hd = d / 2f;

        // bandeja: frente da caixa levada ao plano z = 0, quad CASE com o interior da bandeja inset
        BoxMath.faceCorners(BoxMath.FRONT, w, h, d, pts);
        for (int i = 0; i < 4; i++) pts[i * 3 + 2] += hd;
        BoxMath.project(pts, 4, rotY, rotX, 0f, 0f, 0f, focal, cx, cy, quad);
        if (caseBmp != null) {
            setRect(srcRect, caseBmp.getWidth(), caseBmp.getHeight());
            drawWithPoly(c, caseBmp, srcRect, quad, 0, 0f);
        }
        float tx = 0.04f * w, ty = 0.03f * h; // bandeja: 4% da largura e 3% da altura por lado
        pts[0] += tx; pts[1] += ty;
        pts[3] -= tx; pts[4] += ty;
        pts[6] -= tx; pts[7] -= ty;
        pts[9] += tx; pts[10] -= ty;
        BoxMath.project(pts, 4, rotY, rotX, 0f, 0f, 0f, focal, cx, cy, quad2);
        if (trayBmp != null) {
            setRect(srcRect, trayBmp.getWidth(), trayBmp.getHeight());
            drawWithPoly(c, trayBmp, srcRect, quad2, 0, 0f);
        }
        strokeQuad(c, quad, 0);

        // disco: centralizado na bandeja; em DISC_OUT só avança em z e gira
        float dv = disc.valueAt(now);
        float dr = 0.40f * w;
        float dz = -0.18f * h * dv;
        if (dv > 0.005f) { // sombra sob o disco, deslocada para baixo
            float sy = 0.05f * h;
            pts[0] = -dr; pts[1] = -dr + sy; pts[2] = 0f;
            pts[3] = dr; pts[4] = -dr + sy; pts[5] = 0f;
            pts[6] = dr; pts[7] = dr + sy; pts[8] = 0f;
            pts[9] = -dr; pts[10] = dr + sy; pts[11] = 0f;
            BoxMath.project(pts, 4, rotY, rotX, 0f, 0f, 0f, focal, cx, cy, quad);
            rect.set(quad[0], quad[1], quad[0], quad[1]);
            for (int i = 1; i < 4; i++) rect.union(quad[i * 2], quad[i * 2 + 1]);
            shadowPaint.setAlpha(Math.round(90f * dv * entryA));
            c.drawOval(rect, shadowPaint);
        }
        pts[0] = -dr; pts[1] = -dr; pts[2] = dz;
        pts[3] = dr; pts[4] = -dr; pts[5] = dz;
        pts[6] = dr; pts[7] = dr; pts[8] = dz;
        pts[9] = -dr; pts[10] = dr; pts[11] = dz;
        BoxMath.project(pts, 4, rotY, rotX, 0f, 0f, 0f, focal, cx, cy, quad);
        if (discBmp != null) {
            setRect(srcRect, discBmp.getWidth(), discBmp.getHeight());
            drawWithPoly(c, discBmp, srcRect, quad, 0, spinDeg);
        }

        // tampa: gira em torno da dobradiça deslocada (aresta esquerda da frente), moldura CASE
        float lidDeg = op * 165f;
        BoxMath.faceCorners(BoxMath.FRONT, w, h, d, pts);
        BoxMath.rotateYAround(pts, 4, -hw, -hd, lidDeg);
        BoxMath.project(pts, 4, rotY, rotX, 0f, 0f, 0f, focal, cx, cy, quad);
        if (caseBmp != null) {
            setRect(srcRect, caseBmp.getWidth(), caseBmp.getHeight());
            drawWithPoly(c, caseBmp, srcRect, quad, 0, 0f);
        }
        BoxMath.faceCorners(BoxMath.FRONT, w, h, d, pts);
        float lx = 0.07f * w, ly = 0.05f * h; // capa: 7% da largura e 5% da altura por lado
        pts[0] += lx; pts[1] += ly;
        pts[3] -= lx; pts[4] += ly;
        pts[6] -= lx; pts[7] -= ly;
        pts[9] += lx; pts[10] -= ly;
        BoxMath.rotateYAround(pts, 4, -hw, -hd, lidDeg);
        BoxMath.project(pts, 4, rotY, rotX, 0f, 0f, 0f, focal, cx, cy, quad2);
        if (lidDeg > 90f) { // invertida na horizontal: troca TL com TR e BL com BR
            swapPoint(quad2, 0, 1);
            swapPoint(quad2, 3, 2);
        }
        if (cover != null) drawWithPoly(c, cover, cropSrc, quad2, 0, 0f);
        strokeQuad(c, quad, 0);
    }

    private void drawFace(Canvas c, int f, float[] dst, int off) {
        Bitmap bmp;
        switch (f) {
            case BoxMath.FRONT: bmp = cover; break;
            case BoxMath.BACK: bmp = backBmp; break;
            case BoxMath.SPINE: bmp = spineBmp; break;
            case BoxMath.EDGE: bmp = sideBmp; break;
            default: bmp = caseBmp; break; // TOP, BOTTOM
        }
        if (bmp == null) return;
        if (f == BoxMath.FRONT) {
            drawWithPoly(c, bmp, cropSrc, dst, off, 0f);
        } else {
            setRect(srcRect, bmp.getWidth(), bmp.getHeight());
            drawWithPoly(c, bmp, srcRect, dst, off, 0f);
        }
    }

    private void drawWithPoly(Canvas c, Bitmap bmp, float[] src, float[] dst, int off, float rotDeg) {
        if (bmp == null || bmp.isRecycled()) return;
        matrix.reset();
        if (!matrix.setPolyToPoly(src, 0, dst, off, 4)) return;
        if (rotDeg != 0f) matrix.preRotate(rotDeg, bmp.getWidth() / 2f, bmp.getHeight() / 2f);
        c.drawBitmap(bmp, matrix, bitmapPaint);
    }

    /** Rótulo do estado e nome à esquerda; dicas em coluna à direita, cada uma com a tecla num círculo. */
    private void drawLabels(Canvas c, float w, float h) {
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.DEFAULT);

        textPaint.setTextSize(0.05f * h);
        textPaint.setLetterSpacing(0.24f);
        applyAlpha(textPaint, 170);
        c.drawText(STATE_LABEL[state], 0.05f * w, 0.40f * h, textPaint);
        textPaint.setLetterSpacing(0f);

        if (!name.isEmpty()) {
            textPaint.setTypeface(mediumTypeface);
            textPaint.setTextSize(0.09f * h);
            applyAlpha(textPaint, 255);
            c.drawText(fittedName(w, h), 0.05f * w, 0.54f * h, textPaint);
            textPaint.setTypeface(Typeface.DEFAULT);
        }

        float kr = 0.038f * h;
        float step = 0.13f * h;
        float y = 0.5f * h - (HINTS[state].length - 1) * step / 2f;
        float kx = 0.78f * w;
        textPaint.setTextSize(0.06f * h);
        for (int i = 0; i < HINTS[state].length; i++) {
            String s = HINTS[state][i];
            if (s.equals("OK Jogar")) s = playLabel;
            int sp = s.indexOf(' ');
            char c0 = s.charAt(0);
            int color = c0 == 'O' ? KEY_OK : c0 == 'X' ? KEY_X : c0 == 'V' ? KEY_BACK : KEY_ARROWS;
            circlePaint.setColor(color);
            circlePaint.setAlpha(Math.round((color >>> 24) * entryA));
            c.drawCircle(kx, y, kr, circlePaint);
            if (sp > 0) { // tecla dentro do círculo, encolhida até caber
                keyPaint.setTextSize(kr * 1.15f);
                float kw = keyPaint.measureText(s, 0, sp);
                float max = kr * 1.6f;
                if (kw > max) keyPaint.setTextSize(kr * 1.15f * max / kw);
                applyAlpha(keyPaint, 210);
                c.drawText(s, 0, sp, kx, y - (keyPaint.ascent() + keyPaint.descent()) / 2f, keyPaint);
            }
            applyAlpha(textPaint, 210);
            c.drawText(s, sp + 1, s.length(), kx + kr * 1.6f,
                    y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint);
            y += step;
        }
    }

    /** Nome truncado com reticências em 0.27W; recalculado só quando W/H mudam. */
    private String fittedName(float w, float h) {
        if (fittedName != null && fittedW == (int) w && fittedH == (int) h) return fittedName;
        fittedW = (int) w;
        fittedH = (int) h;
        float max = 0.27f * w;
        if (textPaint.measureText(name) <= max) {
            fittedName = name;
            return name;
        }
        int n = name.length();
        while (n > 0 && textPaint.measureText(name, 0, n) + textPaint.measureText(ELLIPSIS) > max) n--;
        fittedName = name.substring(0, n).trim() + ELLIPSIS;
        return fittedName;
    }

    private void applyAlpha(Paint p, int base) {
        p.setAlpha(Math.round(base * entryA));
    }

    // ---- geometria auxiliar ----

    /** Cantos TL, TR, BR, BL da frente da caixa, inset de edge, no plano z. */
    private void setInsetFront(float w, float h, float edge, float z) {
        float hw = w / 2f - edge, hh = h / 2f - edge;
        pts[0] = -hw; pts[1] = -hh; pts[2] = z;
        pts[3] = hw; pts[4] = -hh; pts[5] = z;
        pts[6] = hw; pts[7] = hh; pts[8] = z;
        pts[9] = -hw; pts[10] = hh; pts[11] = z;
    }

    private void setCropSource() {
        float sw = cover.getWidth(), sh = cover.getHeight();
        float cw = sw, ch = sh, l = 0f, t = 0f;
        if (sw / sh > FRONT_ASPECT) {
            cw = sh * FRONT_ASPECT;
            l = (sw - cw) / 2f;
        } else {
            ch = sw / FRONT_ASPECT;
            t = (sh - ch) / 2f;
        }
        setRectAt(cropSrc, l, t, l + cw, t + ch);
    }

    private static void setRect(float[] o, float w, float h) {
        setRectAt(o, 0f, 0f, w, h);
    }

    /** Cantos TL, TR, BR, BL de um retângulo. */
    private static void setRectAt(float[] o, float l, float t, float r, float b) {
        o[0] = l; o[1] = t;
        o[2] = r; o[3] = t;
        o[4] = r; o[5] = b;
        o[6] = l; o[7] = b;
    }

    private void drawQuad(Canvas c, float[] q, int off, Paint p) {
        path.reset();
        path.moveTo(q[off], q[off + 1]);
        path.lineTo(q[off + 2], q[off + 3]);
        path.lineTo(q[off + 4], q[off + 5]);
        path.lineTo(q[off + 6], q[off + 7]);
        path.close();
        c.drawPath(path, p);
    }

    /** Contorno claro do quad inteiro, por cima da face acabada de desenhar. */
    private void strokeQuad(Canvas c, float[] q, int off) {
        outlinePath.reset();
        outlinePath.moveTo(q[off], q[off + 1]);
        outlinePath.lineTo(q[off + 2], q[off + 3]);
        outlinePath.lineTo(q[off + 4], q[off + 5]);
        outlinePath.lineTo(q[off + 6], q[off + 7]);
        outlinePath.close();
        c.drawPath(outlinePath, outlinePaint);
    }

    private static void swapPoint(float[] q, int i, int j) {
        float x = q[i * 2], y = q[i * 2 + 1];
        q[i * 2] = q[j * 2];
        q[i * 2 + 1] = q[j * 2 + 1];
        q[j * 2] = x;
        q[j * 2 + 1] = y;
    }

    // ---- bitmaps ----

    private static Bitmap solidBmp(int color) {
        return Bitmap.createBitmap(new int[]{color}, 1, 1, Bitmap.Config.ARGB_8888);
    }

    private void releaseBitmaps() {
        backBmp = recycle(backBmp);
        spineBmp = recycle(spineBmp);
        trayBmp = recycle(trayBmp);
        discBmp = recycle(discBmp);
        caseBmp = recycle(caseBmp);
        sideBmp = recycle(sideBmp);
        cover = null;
    }

    private static Bitmap recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
        return null;
    }

    // ---- animação ----

    /**
     * Grandeza animada: vai de from a to em dur, com easeOut por tempo.
     * start < 0 significa "começa no próximo draw", assim a transição usa o tempo do quadro.
     */
    private final class Anim {
        final float dur;
        float from, to;
        long start = -1;

        Anim(float dur) {
            this.dur = dur;
        }

        float valueAt(long now) {
            if (start < 0) return from;
            float t = (now - start) / dur;
            return from + (to - from) * BoxMath.easeOut(t);
        }

        boolean busy(long now) {
            return from != to && (start < 0 || now - start < dur);
        }

        void begin(long now) {
            if (start < 0) start = now;
        }

        void snap(float v) {
            from = v;
            to = v;
            start = -1;
        }

        void set(float target) {
            if (target == to) return;
            if (!animate) {
                from = target;
                to = target;
                start = -1;
                return;
            }
            from = valueAt(lastNow);
            to = target;
            start = -1;
        }
    }
}
