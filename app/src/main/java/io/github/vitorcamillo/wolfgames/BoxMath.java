package io.github.vitorcamillo.wolfgames;

/**
 * Matemática 3D pura da prateleira de caixas (sem Android, roda em teste JVM).
 *
 * Convenção: x para a direita, y para baixo, z para longe do espectador.
 * Rotação em Y por a graus: x' = x*cos(a) + z*sin(a), z' = -x*sin(a) + z*cos(a).
 * Rotação em X por b graus: y' = y*cos(b) - z*sin(b), z' = y*sin(b) + z*cos(b).
 * A rotação Y aplica primeiro, depois X, depois a translação. Projeção: s = focal/(focal+z).
 *
 * Métodos chamados a cada quadro não alocam memória.
 */
final class BoxMath {

    static final int FRONT = 0, BACK = 1, SPINE = 2, EDGE = 3, TOP = 4, BOTTOM = 5;
    static final float SIDE_ANGLE = 60f;
    static final float CENTER_ANGLE = -22f;
    static final int SHELF_REACH = 9;

    private BoxMath() {}

    /**
     * Slot da prateleira para offset (índice do item menos posição animada).
     * out = {x, rotYDeg, z}, em larguras de caixa.
     *
     * Ponto central: x=0, rotY=CENTER_ANGLE, z=-1.05. Ponto lateral para a>=1:
     * x=sg*(1.22+(a-1)*0.283), rotY=sg*SIDE_ANGLE, z=0.70+a*0.0208.
     * Para u=abs(offset)>=1 usa o lateral com a=u; para u<1 interpola linearmente
     * do central ao lateral com a=1, fator u.
     */
    static void slot(float offset, float[] out) {
        float u = Math.abs(offset);
        float sg = offset < 0f ? -1f : 1f;
        if (u >= 1f) {
            out[0] = sg * (1.22f + (u - 1f) * 0.283f);
            out[1] = sg * SIDE_ANGLE;
            out[2] = 0.70f + u * 0.0208f;
        } else {
            float sideX = sg * 1.22f;
            float sideRotY = sg * SIDE_ANGLE;
            float sideZ = 0.70f + 0.0208f;
            out[0] = sideX * u;
            out[1] = CENTER_ANGLE + (sideRotY - CENTER_ANGLE) * u;
            out[2] = -1.05f + (sideZ + 1.05f) * u;
        }
    }

    /**
     * Preenche out[12] com os 4 cantos 3D (TL, TR, BR, BL vistos de fora da face)
     * de uma caixa w, h, d centrada na origem.
     */
    static void faceCorners(int face, float w, float h, float d, float[] out) {
        float hw = w / 2f, hh = h / 2f, hd = d / 2f;
        switch (face) {
            case FRONT: // z = -hd
                put(out, 0, -hw, -hh, -hd);
                put(out, 3, hw, -hh, -hd);
                put(out, 6, hw, hh, -hd);
                put(out, 9, -hw, hh, -hd);
                break;
            case BACK: // z = +hd
                put(out, 0, hw, -hh, hd);
                put(out, 3, -hw, -hh, hd);
                put(out, 6, -hw, hh, hd);
                put(out, 9, hw, hh, hd);
                break;
            case SPINE: // x = -hw
                put(out, 0, -hw, -hh, hd);
                put(out, 3, -hw, -hh, -hd);
                put(out, 6, -hw, hh, -hd);
                put(out, 9, -hw, hh, hd);
                break;
            case EDGE: // x = +hw
                put(out, 0, hw, -hh, -hd);
                put(out, 3, hw, -hh, hd);
                put(out, 6, hw, hh, hd);
                put(out, 9, hw, hh, -hd);
                break;
            case TOP: // y = -hh
                put(out, 0, -hw, -hh, hd);
                put(out, 3, hw, -hh, hd);
                put(out, 6, hw, -hh, -hd);
                put(out, 9, -hw, -hh, -hd);
                break;
            case BOTTOM: // y = +hh
                put(out, 0, -hw, hh, -hd);
                put(out, 3, hw, hh, -hd);
                put(out, 6, hw, hh, hd);
                put(out, 9, -hw, hh, hd);
                break;
            default:
                throw new IllegalArgumentException("face inválida: " + face);
        }
    }

    private static void put(float[] out, int i, float x, float y, float z) {
        out[i] = x;
        out[i + 1] = y;
        out[i + 2] = z;
    }

    /** Gira n pontos (x, y, z consecutivos) em volta do eixo vertical que passa por (pivotX, pivotZ), no lugar. */
    static void rotateYAround(float[] pts, int n, float pivotX, float pivotZ, float deg) {
        double rad = Math.toRadians(deg);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        for (int i = 0; i < n; i++) {
            int k = i * 3;
            float x = pts[k] - pivotX;
            float z = pts[k + 2] - pivotZ;
            pts[k] = x * c + z * s + pivotX;
            pts[k + 2] = -x * s + z * c + pivotZ;
        }
    }

    /**
     * Aplica rotY, rotX, translação e projeção a n pontos; out2 recebe x, y consecutivos.
     * Retorna a média de z após a translação (0 se n <= 0).
     */
    static float project(float[] pts3, int n, float rotYDeg, float rotXDeg,
                         float tx, float ty, float tz, float focal, float cx, float cy, float[] out2) {
        if (n <= 0) return 0f;
        float ay = (float) Math.toRadians(rotYDeg);
        float ax = (float) Math.toRadians(rotXDeg);
        float cosY = (float) Math.cos(ay), sinY = (float) Math.sin(ay);
        float cosX = (float) Math.cos(ax), sinX = (float) Math.sin(ax);
        float sumZ = 0f;
        for (int i = 0; i < n; i++) {
            int k = i * 3;
            float x = pts3[k], y = pts3[k + 1], z = pts3[k + 2];
            // rotação Y
            float x1 = x * cosY + z * sinY;
            float z1 = -x * sinY + z * cosY;
            // rotação X
            float y1 = y * cosX - z1 * sinX;
            float z2 = y * sinX + z1 * cosX;
            // translação
            float X = x1 + tx;
            float Y = y1 + ty;
            float Z = z2 + tz;
            sumZ += Z;
            // projeção
            float sc = focal / (focal + Z);
            out2[i * 2] = cx + X * sc;
            out2[i * 2 + 1] = cy + Y * sc;
        }
        return sumZ / n;
    }

    /**
     * Verdadeiro se o quad projetado (TL, TR, BR, BL em y para baixo) está de frente,
     * isto é, área assinada positiva pela fórmula do cadarço.
     */
    static boolean facing(float[] quad8) {
        float area = 0f;
        for (int i = 0; i < 4; i++) {
            int a = i * 2;
            int b = ((i + 1) % 4) * 2;
            area += quad8[a] * quad8[b + 1] - quad8[b] * quad8[a + 1];
        }
        return area > 0f;
    }

    /** Desaceleração cúbica: 1 - (1 - t)^3, com t limitado a 0..1. */
    static float easeOut(float t) {
        float u = 1f - clampF(t, 0f, 1f);
        return 1f - u * u * u;
    }

    /** Limita v a min..max. */
    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clampF(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Faixa inclusiva {primeiro, último} de índices visíveis para count itens e posição pos,
     * com alcance SHELF_REACH. count 0 retorna {0, -1}.
     */
    static int[] visibleRange(float pos, int count) {
        if (count <= 0) return new int[]{0, -1};
        int first = clamp((int) Math.floor(pos) - SHELF_REACH, 0, count - 1);
        int last = clamp((int) Math.ceil(pos) + SHELF_REACH, 0, count - 1);
        return new int[]{first, last};
    }
}
