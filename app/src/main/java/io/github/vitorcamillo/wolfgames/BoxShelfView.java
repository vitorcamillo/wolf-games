package io.github.vitorcamillo.wolfgames;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fileira 3D de jogos dentro da Home, desenhada só com Canvas (sem OpenGL): a View ocupa apenas
 * o retângulo entre o cabeçalho e o dock, com fundo transparente — o resto da Home continua visível.
 *
 * Índices de "game" são do catálogo; a posição visual é o índice na lista recebida em setItems.
 * Nenhum Handler ou animador: o redesenho contínuo é um postInvalidateDelayed encadeado em onDraw,
 * que só segue enquanto há o que animar (foco de teclado, slide ou inspeção) e a View está mostrada.
 */
final class BoxShelfView extends View implements BoxInspect.Host {

    interface Listener {
        void launch(int game);
        void exitDown();
        void exitUp();
        void selected(int game);
        void reorder(int game);
        /** Cor do jogo selecionado (também disparada em setItems), para o chamador tingir quem quiser. */
        void tint(int color);
    }

    static final class Item {
        final int game;
        final String name;
        final Bitmap cover;

        Item(int game, String name, Bitmap cover) {
            this.game = game;
            this.name = name;
            this.cover = cover;
        }
    }

    private static final int DEFAULT_TINT = 0xff2a6f5a;
    private static final int CASE = 0xff1b1f27;
    private static final int SIDE = 0xff11141a;
    private static final int FILM = 0x57000000;          // película preta alfa .34
    private static final int OUTLINE = 0xb3ffffff;       // contorno branco alfa .7
    private static final int SLIDE_MS = 550;
    private static final int TINT_MS = 400;
    private static final int FRAME_MS = 33;
    private static final int SPINE_MAX = 24;
    private static final int SPINE_BUILD_PER_FRAME = 2;
    private static final float BOX_H = 0.66f;    // altura da caixa, em fração de H
    private static final float BOX_W = 0.667f;   // largura da caixa, em fração da altura
    private static final float BOX_D = 0.07f;    // profundidade, em fração da largura
    private static final float CY = 0.41f;       // centro vertical da caixa, em fração de H
    private static final float FOCAL = 4.17f;    // distância focal, em fração da altura da caixa
    private static final float EDGE = 0.015f;    // moldura da capa no FRONT, em fração da largura
    private static final int HALO_MS = 3400;
    private static final float TWO_PI = (float) (Math.PI * 2);
    private static final String BADGE = "EM ANDAMENTO";
    private static final String ELLIPSIS = "…";

    private final Listener listener;
    private final boolean animate;
    private final float density;
    private final BoxInspect inspect;

    private final List<Item> items = new ArrayList<>();
    private final Map<Integer, Integer> tints = new HashMap<>();
    private final Map<Integer, Bitmap> placeholders = new HashMap<>();
    private final Map<Integer, Bitmap> spines = new LinkedHashMap<Integer, Bitmap>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> eldest) {
            if (size() <= SPINE_MAX) return false;
            recycle(eldest.getValue());
            return true;
        }
    };

    private int selIdx = -1;
    private int running = -1;
    private boolean keyboardActive;
    private int vw, vh;
    private boolean tintInit;
    private int tintFrom = DEFAULT_TINT, tintTo = DEFAULT_TINT;
    private long tintT0;
    private int haloW = -1, haloH = -1, haloGlow = 0;
    private int floorW = -1, floorH = -1, floorGlow = 0;
    private Bitmap overlayBmp;
    private Bitmap snap;
    private Canvas snapCanvas;
    private int spineH = -1;

    private float posFrom, posTarget;
    private long posT0;

    private boolean okDown, okReordered;

    // geometria do quadro atual
    private float gBh, gBw, gBd, gFocal, gCx, gCy, gFloor, gTx, gTz, gEdge;

    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint reflPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint snapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floorGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final Path fillPath = new Path();
    private final Path outlinePath = new Path();
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final float[] srcPts = new float[8];
    private final float[] pts = new float[12];
    private final float[] quad = new float[8];
    private final float[] frontQ = new float[8];
    private final float[] mirrorQ = new float[8];
    private final float[] slot = new float[3];
    private int[] order = new int[32];
    private Bitmap[] visSpine = new Bitmap[32];

    BoxShelfView(Context context, Listener listener, boolean animate) {
        super(context);
        this.listener = listener;
        this.animate = animate;
        this.density = context.getResources().getDisplayMetrics().density;
        this.inspect = new BoxInspect(this, density, animate);
        setFocusable(false);
        setWillNotDraw(false);

        reflPaint.setAlpha(38);
        fill.setStyle(Paint.Style.FILL);
        haloPaint.setStyle(Paint.Style.FILL);
        floorGlowPaint.setStyle(Paint.Style.FILL);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(2f * density);
        outlinePaint.setColor(OUTLINE);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setShadowLayer(12f * density, 0f, 0f, Color.BLACK);
        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        pillPaint.setStyle(Paint.Style.FILL);
    }

    // ---- API pública da prateleira ----

    /** Lista em ordem visual; game é o índice no catálogo. Mantém o selecionado se ele continuar na lista. */
    void setItems(List<Item> newItems, int selectedGame) {
        int previous = selectedGame();
        items.clear();
        if (newItems != null) {
            for (Item it : newItems) {
                if (it != null) items.add(it);
            }
        }
        tints.clear();
        int idx = indexOfGame(previous);
        if (idx < 0) idx = indexOfGame(selectedGame);
        if (idx < 0 && !items.isEmpty()) idx = 0;
        selIdx = idx;
        posFrom = posTarget = Math.max(0, idx);
        posT0 = 0L;
        listener.tint(idx >= 0 ? tintOf(items.get(idx)) : DEFAULT_TINT);
        updateDescription();
        invalidate();
    }

    /** Jogo em andamento, -1 para nenhum. */
    void setRunning(int game) {
        running = game;
        invalidate();
    }

    void setHost(String label) {
        // rótulo do host deixou de ser exibido; o método permanece pela API existente
    }

    boolean inspecting() {
        return inspect.active();
    }

    /** Foco de teclado na fileira: liga contorno, halo e brilho do chão no item selecionado. */
    void setKeyboardActive(boolean on) {
        if (keyboardActive == on) return;
        keyboardActive = on;
        invalidate();
    }

    void select(int game) {
        int idx = indexOfGame(game);
        if (idx < 0 || idx == selIdx) return;
        applySelection(idx);
    }

    int selectedGame() {
        return selIdx >= 0 ? items.get(selIdx).game : -1;
    }

    /** Trata DOWN e UP de tecla enquanto a prateleira está aberta. Sempre retorna true para teclas D-pad, OK, A, X e BACK. */
    boolean dispatch(KeyEvent event) {
        if (event == null) return false;
        int code = normalize(event.getKeyCode());
        if (!handled(code)) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = down && event.getRepeatCount() > 0;

        if (inspect.active()) {
            if (down && !repeat) inspect.key(code);
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                pressOk(down, repeat);
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (down && !repeat && selIdx >= 0) listener.launch(selectedGame());
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BACK:
                if (down && !repeat) listener.exitDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && !repeat) listener.exitUp();
                return true;
            default:
                return true;
        }
    }

    /** Volta da inspeção para a prateleira, sem animação. */
    void closeInspect() {
        inspect.close();
        recycleSnap();
        invalidate();
    }

    /** Recicla só o que esta classe gerou; nunca as capas recebidas. Idempotente. */
    void release() {
        recycleSpines();
        for (Bitmap b : placeholders.values()) recycle(b);
        placeholders.clear();
        recycle(overlayBmp);
        overlayBmp = null;
        recycleSnap();
        tints.clear();
        inspect.release();
    }

    // ---- BoxInspect.Host ----

    @Override
    public void invalidateInspect() {
        invalidate();
    }

    @Override
    public void inspectClosed() {
        recycleSnap();
        invalidate();
    }

    @Override
    public void inspectPlay() {
        int game = selectedGame();
        if (game >= 0) listener.launch(game);
    }

    // ---- ciclo de vida da View ----

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        vw = w;
        vh = h;
        long now = SystemClock.uptimeMillis();
        if (spineH != vh) {
            recycleSpines();
            spineH = vh;
        }
        setGeometry();

        boolean inspecting = inspect.active();
        if (inspecting && snap != null && !snap.isRecycled()) {
            // durante a inspeção, só o retrato estático da cena e a própria inspeção
            rect.set(0, 0, vw, vh);
            c.drawBitmap(snap, null, rect, snapPaint);
            inspect.draw(c, vw, vh, now);
        } else {
            drawScene(c, currentPos(now), now, !inspecting);
            if (inspecting) inspect.draw(c, vw, vh, now);
        }

        if (animate && isShown()
                && (keyboardActive || inspecting || now - posT0 < SLIDE_MS)) {
            postInvalidateDelayed(FRAME_MS);
        }
    }

    // ---- entrada ----

    private static int normalize(int code) {
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return KeyEvent.KEYCODE_DPAD_CENTER;
        }
        return code;
    }

    private static boolean handled(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    private void step(int delta) {
        int n = items.size();
        if (n == 0 || selIdx < 0) return;
        int next = BoxMath.clamp(selIdx + delta, 0, n - 1);
        if (next == selIdx) return;
        applySelection(next);
        listener.selected(items.get(next).game);
    }

    /** OK: repetição segura o item para reordenar; sem repetição, abre a inspeção no UP. */
    private void pressOk(boolean down, boolean repeat) {
        if (down) {
            if (!repeat) {
                okDown = true;
                okReordered = false;
            } else if (okDown && !okReordered) {
                okReordered = true;
                if (selIdx >= 0) listener.reorder(selectedGame());
            }
        } else if (okDown) {
            boolean open = !okReordered;
            okDown = false;
            okReordered = false;
            if (open) openInspect();
        }
    }

    private void openInspect() {
        if (selIdx < 0) return;
        Item it = items.get(selIdx);
        inspect.open(it.cover, tintOf(it), it.name, it.game == running);
        makeSnapshot();
        invalidate();
    }

    // ---- seleção e posição ----

    private void applySelection(int idx) {
        moveTo(idx);
        selIdx = idx;
        listener.tint(tintOf(items.get(idx)));
        updateDescription();
        invalidate();
    }

    private void moveTo(int idx) {
        long now = SystemClock.uptimeMillis();
        posFrom = animate ? currentPos(now) : idx;
        posTarget = idx;
        posT0 = now;
    }

    private float currentPos(long now) {
        if (!animate || now >= posT0 + SLIDE_MS) return posTarget;
        float f = BoxMath.easeOut((now - posT0) / (float) SLIDE_MS);
        return posFrom + (posTarget - posFrom) * f;
    }

    private int indexOfGame(int game) {
        if (game < 0) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).game == game) return i;
        }
        return -1;
    }

    private void updateDescription() {
        setContentDescription(selIdx >= 0 ? items.get(selIdx).name : null);
    }

    // ---- cor ----

    private int selectedTint() {
        return selIdx >= 0 ? tintOf(items.get(selIdx)) : DEFAULT_TINT;
    }

    private int tintOf(Item it) {
        Integer t = tints.get(it.game);
        if (t == null) {
            t = BoxArt.tint(it.cover);
            tints.put(it.game, t);
        }
        return t;
    }

    private int tintNow(long now, int target) {
        if (!tintInit) {
            tintInit = true;
            tintFrom = tintTo = target;
        }
        if (target != tintTo) {
            tintFrom = tintAt(now);
            tintTo = target;
            tintT0 = now;
        }
        return tintAt(now);
    }

    private int tintAt(long now) {
        if (!animate || now >= tintT0 + TINT_MS) return tintTo;
        return lerpColor(tintFrom, tintTo, (now - tintT0) / (float) TINT_MS);
    }

    /** Tint clareado: cor do brilho de foco. */
    private static int lighten(int c) {
        return lerpColor(c, 0xffffffff, 0.55f);
    }

    // ---- desenho: cena da fileira ----

    private void setGeometry() {
        gBh = BOX_H * vh;
        gBw = BOX_W * gBh;
        gBd = BOX_D * gBw;
        gFocal = FOCAL * gBh;
        gCx = vw / 2f;
        gCy = CY * vh;
        gFloor = gCy + gBh / 2f;
        gEdge = EDGE * gBw;
    }

    /** Um quadro da cena: halo, brilho do chão, caixas, reflexo e (sem inspeção) título. */
    private void drawScene(Canvas c, float pos, long now, boolean withTitle) {
        int glow = lighten(tintNow(now, selectedTint()));
        if (keyboardActive) drawHalo(c, glow, now);
        if (keyboardActive) drawFloorGlow(c, glow);
        drawBoxes(c, pos, glow);
        drawSelectedReflection(c, pos);
        if (withTitle) drawTitle(c);
    }

    private void drawHalo(Canvas c, int glow, long now) {
        if (haloW != vw || haloH != vh || haloGlow != glow) {
            int rgb = glow & 0xffffff;
            haloPaint.setShader(new RadialGradient(gCx, gCy, gBw,
                    (0x73 << 24) | rgb, rgb, Shader.TileMode.CLAMP));
            haloW = vw;
            haloH = vh;
            haloGlow = glow;
        }
        // alfa global oscila entre 1 e .55 num ciclo de 3400 ms
        float pulse = animate ? 0.775f - 0.225f * (float) Math.cos(now * TWO_PI / HALO_MS) : 1f;
        haloPaint.setAlpha(Math.round(255f * pulse));
        c.drawRect(gCx - gBw, gCy - 0.75f * gBh, gCx + gBw, gCy + 0.75f * gBh, haloPaint);
    }

    // ---- desenho: caixas ----

    /** Posiciona o item na prateleira; preenche gTx/gTz e devolve rotY. */
    private float placeItem(float off) {
        BoxMath.slot(off, slot);
        gTx = slot[0] * gBw;
        gTz = slot[2] * gBw;
        return slot[1];
    }

    private void drawBoxes(Canvas c, float pos, int glow) {
        int n = items.size();
        if (n == 0) return;
        int[] range = BoxMath.visibleRange(pos, n);
        int first = range[0];
        int count = range[1] - first + 1;
        if (count <= 0) return;
        if (order.length < count) {
            order = new int[count * 2];
            visSpine = new Bitmap[count * 2];
        }

        // lombadas já em cache (toca a ordem LRU) e construção limitada por quadro
        for (int k = 0; k < count; k++) {
            order[k] = first + k;
            visSpine[k] = spines.get(items.get(first + k).game);
        }
        int built = 0;
        for (int k = 0; k < count && built < SPINE_BUILD_PER_FRAME; k++) {
            if (visSpine[k] != null) continue;
            Item it = items.get(first + k);
            Bitmap sp = buildSpine(it);
            spines.put(it.game, sp);
            visSpine[k] = sp;
            built++;
        }

        // da mais distante para a mais próxima
        for (int a = 1; a < count; a++) {
            int v = order[a];
            float dv = Math.abs(v - pos);
            int b = a - 1;
            while (b >= 0 && Math.abs(order[b] - pos) < dv) {
                order[b + 1] = order[b];
                b--;
            }
            order[b + 1] = v;
        }

        for (int k = 0; k < count; k++) {
            int i = order[k];
            float rotY = placeItem(i - pos);
            drawBox(c, items.get(i), visSpine[i - first], i == selIdx, rotY, glow);
        }
    }

    private void drawBox(Canvas c, Item it, Bitmap spine, boolean selected, float rotY, int glow) {
        boolean film = !selected || !keyboardActive; // sem foco, o selecionado também leva película
        boolean frontSeen = faceQuad(BoxMath.FRONT, rotY);
        if (frontSeen) {
            System.arraycopy(quad, 0, frontQ, 0, 8);
            fillQuad(c, frontQ, CASE);
            if (faceQuadInset(rotY)) {
                drawFace(c, frontBitmap(it), quad, facePaint);
                overlayPaint.setAlpha(film ? 140 : 255); // não selecionados: capa coberta a 55%
                drawFace(c, overlay(), quad, overlayPaint);
            }
            if (film) fillQuad(c, frontQ, FILM);
        }
        if (faceQuad(BoxMath.SPINE, rotY) && spine != null) {
            drawFace(c, spine, quad, facePaint);
            if (film) fillQuad(c, quad, FILM);
        }
        if (faceQuad(BoxMath.EDGE, rotY)) fillQuad(c, quad, SIDE);
        if (faceQuad(BoxMath.TOP, rotY)) fillQuad(c, quad, SIDE);
        if (frontSeen && selected && keyboardActive) drawFocus(c);
        if (frontSeen && it.game == running) drawBadge(c, frontQ);
    }

    /** Preenche quad com a face projetada; devolve se ela está de frente. */
    private boolean faceQuad(int face, float rotY) {
        BoxMath.faceCorners(face, gBw, gBh, gBd, pts);
        BoxMath.project(pts, 4, rotY, 0f, gTx, 0f, gTz, gFocal, gCx, gCy, quad);
        return BoxMath.facing(quad);
    }

    /** Quad FRONT com a moldura de edge em cada lado, para a capa. */
    private boolean faceQuadInset(float rotY) {
        BoxMath.faceCorners(BoxMath.FRONT, gBw - 2f * gEdge, gBh - 2f * gEdge, gBd, pts);
        BoxMath.project(pts, 4, rotY, 0f, gTx, 0f, gTz, gFocal, gCx, gCy, quad);
        return BoxMath.facing(quad);
    }

    private void fillQuad(Canvas c, float[] q, int color) {
        fillPath.reset();
        fillPath.moveTo(q[0], q[1]);
        fillPath.lineTo(q[2], q[3]);
        fillPath.lineTo(q[4], q[5]);
        fillPath.lineTo(q[6], q[7]);
        fillPath.close();
        fill.setColor(color);
        c.drawPath(fillPath, fill);
    }

    /** Desenha bmp esticado sobre o quad de 4 pontos (TL, TR, BR, BL). */
    private void drawFace(Canvas c, Bitmap bmp, float[] q, Paint paint) {
        if (bmp == null || bmp.isRecycled()) return;
        float bw = bmp.getWidth();
        float bh = bmp.getHeight();
        srcPts[0] = 0f;
        srcPts[1] = 0f;
        srcPts[2] = bw;
        srcPts[3] = 0f;
        srcPts[4] = bw;
        srcPts[5] = bh;
        srcPts[6] = 0f;
        srcPts[7] = bh;
        matrix.reset();
        if (matrix.setPolyToPoly(srcPts, 0, q, 0, 4)) {
            c.drawBitmap(bmp, matrix, paint);
        }
    }

    /** Contorno branco de 2 px no Path do quad da caixa selecionada. */
    private void drawFocus(Canvas c) {
        outlinePath.reset();
        outlinePath.moveTo(frontQ[0], frontQ[1]);
        outlinePath.lineTo(frontQ[2], frontQ[3]);
        outlinePath.lineTo(frontQ[4], frontQ[5]);
        outlinePath.lineTo(frontQ[6], frontQ[7]);
        outlinePath.close();
        c.drawPath(outlinePath, outlinePaint);
    }

    private void drawBadge(Canvas c, float[] q) {
        float ps = 0.02f * vh;
        badgeTextPaint.setTextSize(ps);
        float tw = badgeTextPaint.measureText(BADGE);
        float ph = ps * 1.9f;
        float pw = tw + ph;
        float cxq = (q[0] + q[2] + q[4] + q[6]) / 4f;
        float y = 0.05f * vh;
        rect.set(cxq - pw / 2f, y - ph / 2f, cxq + pw / 2f, y + ph / 2f);
        pillPaint.setColor(0xe6101418);
        c.drawRoundRect(rect, ph / 2f, ph / 2f, pillPaint);
        drawCentered(c, BADGE, cxq, y, badgeTextPaint);
    }

    /** Reflexo do FRONT do item selecionado, espelhado no piso e recortado logo abaixo dele. */
    private void drawSelectedReflection(Canvas c, float pos) {
        if (selIdx < 0) return;
        float rotY = placeItem(selIdx - pos);
        if (!faceQuad(BoxMath.FRONT, rotY)) return;
        for (int k = 0; k < 4; k++) {
            mirrorQ[2 * k] = quad[2 * k];
            mirrorQ[2 * k + 1] = 2f * gFloor - quad[2 * k + 1];
        }
        c.save();
        c.clipRect(0, gFloor, vw, gFloor + 0.12f * vh);
        drawFace(c, frontBitmap(items.get(selIdx)), mirrorQ, reflPaint);
        c.restore();
    }

    /** Brilho do chão atrás das caixas: elipse de gradiente radial, com shader em cache como o do halo. */
    private void drawFloorGlow(Canvas c, int glow) {
        if (floorW != vw || floorH != vh || floorGlow != glow) {
            int rgb = glow & 0xffffff;
            RadialGradient g = new RadialGradient(0f, 0f, 1f,
                    (0x4d << 24) | rgb, rgb, Shader.TileMode.CLAMP);
            matrix.reset();
            matrix.setScale(1.2f * gBw, 0.05f * vh);
            matrix.postTranslate(gCx, gFloor);
            g.setLocalMatrix(matrix);
            floorGlowPaint.setShader(g);
            floorW = vw;
            floorH = vh;
            floorGlow = glow;
        }
        rect.set(gCx - 1.2f * gBw, gFloor - 0.05f * vh,
                gCx + 1.2f * gBw, gFloor + 0.05f * vh);
        c.drawRect(rect, floorGlowPaint);
    }

    // ---- desenho: título ----

    private void drawTitle(Canvas c) {
        String name = selIdx >= 0 ? items.get(selIdx).name : null;
        if (name == null || name.isEmpty()) return;
        textPaint.setTextSize(0.072f * vh);
        drawCentered(c, fitWidth(name, textPaint, 0.7f * vw), gCx, 0.915f * vh, textPaint);
    }

    /** Desenha s com y como centro vertical; x é âncora conforme o alinhamento de p. */
    private void drawCentered(Canvas c, String s, float x, float y, Paint p) {
        p.getFontMetrics(fm);
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, p);
    }

    private static String fitWidth(String s, Paint p, float maxW) {
        if (p.measureText(s) <= maxW) return s;
        float e = p.measureText(ELLIPSIS);
        int n = s.length();
        while (n > 0 && p.measureText(s, 0, n) + e > maxW) n--;
        return s.substring(0, n).trim() + ELLIPSIS;
    }

    // ---- retrato da cena para o fundo da inspeção ----

    /** A cena sem título, capturada uma vez ao abrir; serve de fundo estático durante a inspeção. */
    private void makeSnapshot() {
        recycleSnap();
        if (vw <= 0 || vh <= 0) return;
        Bitmap b = Bitmap.createBitmap(120, 30, Bitmap.Config.ARGB_8888);
        if (snapCanvas == null) snapCanvas = new Canvas();
        snapCanvas.setBitmap(b);
        snapCanvas.save();
        snapCanvas.scale(120f / vw, 30f / vh);
        long now = SystemClock.uptimeMillis();
        drawScene(snapCanvas, currentPos(now), now, false);
        snapCanvas.restore();
        snap = b;
    }

    private void recycleSnap() {
        if (snap != null) {
            recycle(snap);
            snap = null;
        }
    }

    // ---- caches: lombadas, capas substitutas, brilho da capa ----

    private Bitmap frontBitmap(Item it) {
        Bitmap cov = it.cover;
        if (cov != null && !cov.isRecycled() && cov.getWidth() > 0 && cov.getHeight() > 0) return cov;
        Bitmap p = placeholders.get(it.game);
        if (p == null || p.isRecycled()) {
            p = BoxArt.placeholder(it.name, 240, 320);
            placeholders.put(it.game, p);
        }
        return p;
    }

    /**
     * Brilho sobre a capa, gerado uma vez (32x96): clarão branco no topo (alfa .16 até .04 em 18%,
     * a zero em 34%) e faixas pretas nas laterais (.14 nos 10% da esquerda, .18 nos 12% da direita).
     */
    private Bitmap overlay() {
        if (overlayBmp == null) {
            Bitmap b = Bitmap.createBitmap(32, 96, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, 96,
                    new int[]{0x29ffffff, 0x0affffff, 0x00ffffff},
                    new float[]{0f, 0.18f, 0.34f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, 32, 96, p);
            p.setShader(null);
            p.setColor(0x24000000);
            cv.drawRect(0, 0, 3.2f, 96, p);
            p.setColor(0x2e000000);
            cv.drawRect(28.16f, 0, 32, 96, p);
            overlayBmp = b;
        }
        return overlayBmp;
    }

    private Bitmap buildSpine(Item it) {
        int sw = BoxMath.clamp(Math.round(BOX_D * BOX_W * BOX_H * vh), 8, 64);
        int sh = BoxMath.clamp(Math.round(BOX_H * vh), 16, 384);
        return BoxArt.spine(tintOf(it), it.name, sw, sh);
    }

    private void recycleSpines() {
        for (Bitmap b : spines.values()) recycle(b);
        spines.clear();
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    // ---- cor: utilidades sem alocação ----

    private static int lerpColor(int a, int b, float f) {
        return Color.rgb(lerpChannel(Color.red(a), Color.red(b), f),
                lerpChannel(Color.green(a), Color.green(b), f),
                lerpChannel(Color.blue(a), Color.blue(b), f));
    }

    private static int lerpChannel(int a, int b, float f) {
        return Math.round(a + (b - a) * f);
    }
}
