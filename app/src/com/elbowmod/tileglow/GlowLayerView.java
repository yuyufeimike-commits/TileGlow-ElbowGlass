package com.elbowmod.tileglow;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

/**
 * 玻璃体内的「整块加色层」—— v8.0 状态机重写版。
 *
 * <h3>设计原则（替代 v7.x 的七条互相打架的启发式）</h3>
 * <ul>
 *   <li><b>单一真源</b>：面板可见性只由 ROM 的 PanelExpansion 驱动
 *       （fraction 1=展开 / 0=收起，expanded=true 表示稳定展开态）。
 *       收起淡出直接用 fraction，不再有面积比值猜测 → 开关磁贴/媒体卡
 *       改变总面积不会再被误判为"收起"导致全局变暗；</li>
 *   <li><b>单写者</b>：所有易变状态只在 UI 线程写入（ROM 回调与看门狗都 post 到主线程），
 *       渲染线程只提交几何缓冲并请求重绘，杜绝跨线程拉锯；</li>
 *   <li><b>静止零工作</b>：全量 64 位帧指纹（FNV-1a，覆盖 count+42×N float+colors），
 *       逐位相同 → 零 GPU/CPU 工作（G720 TBDR 功耗=带宽+混合，不画是最优）；</li>
 *   <li><b>熄屏断供</b>：非交互状态不出帧；</li>
 *   <li><b>加色混合</b>：输出 = dst + src，透明处加 0 仍透明。</li>
 * </ul>
 *
 * <p>形状/位置/圆角全部来自 Elbow 每帧 geometry（radius = geometry[8]）。
 */
final class GlowLayerView extends View {

    private static final int MAX = 24;
    private static final int STRIDE = 6;   // x, y, w, h, r, gate
    private static final int SEG = 6;      // 边缘光带细分段数

    /** 光色预设：冷白/中性/暖白/天空/玫瑰/香槟。 */
    private static final int[][] TINTS = {
            {0xFC, 0xFD, 0xFF}, {0xFF, 0xFF, 0xFF}, {0xFF, 0xF4, 0xE6},
            {0xE8, 0xF1, 0xFF}, {0xFF, 0xE9, 0xF0}, {0xFF, 0xF2, 0xD9},
    };

    private static float pulseSum(float t) {
        float p1 = 1f - smooth01(Math.abs(t - 0.118f) / 0.20f);
        float p2 = 1f - smooth01(Math.abs(t - 0.238f) / 0.40f);
        return 0.55f * p1 + 0.35f * p2;
    }

    private static float smooth01(float x) {
        x = x < 0f ? 0f : (x > 1f ? 1f : x);
        return x * x * (3f - 2f * x);
    }

    private static int dim(int rgb, float k) {
        return Color.rgb((int) (((rgb >> 16) & 0xFF) * k),
                (int) (((rgb >> 8) & 0xFF) * k), (int) ((rgb & 0xFF) * k));
    }

    // ---- 渲染线程输入（Elbow 每帧几何，双缓冲 + 短锁）----
    private final float[] write = new float[MAX * STRIDE];
    private final float[] draw = new float[MAX * STRIDE];
    private int writeCount;
    private final Object lock = new Object();
    private long lastFrameHash = Long.MIN_VALUE;

    // ---- 配置（UI 线程单写者，volatile 读）----
    private volatile int lift = 90, edge = 35, band = 26, inner = 55, tint = 0;
    private volatile float angleDeg = 90f;
    private volatile boolean swTile = true, swSlider = true, swCard = true, swCapsule = true;

    // ---- 面板状态（UI 线程单写者：ROM 回调 + expanded 锁定）----
    private volatile float panelFraction = 1f;

    private final Paint paintFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] paintSeg = new Paint[SEG];
    private final long[] builtSig = new long[MAX];
    private final Shader[] builtFill = new Shader[MAX];
    private final Shader[] builtSweep = new Shader[MAX];
    private final android.os.PowerManager pm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    GlowLayerView(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
        paintFill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < SEG; i++) {
            paintSeg[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintSeg[i].setStyle(Paint.Style.STROKE);
            paintSeg[i].setBlendMode(BlendMode.PLUS);
        }
        paintFill.setBlendMode(BlendMode.PLUS);
    }

    /** 设置页/watchdog 推送配置（UI 线程）。 */
    void apply(Config.Snapshot s) {
        lift = s.lift;
        edge = s.edge;
        band = s.band;
        inner = s.inner;
        tint = s.tint;
        angleDeg = (float) Math.toDegrees(s.angleRad);
        swTile = s.swTile;
        swSlider = s.swSlider;
        swCard = s.swCard;
        swCapsule = s.swCapsule;
    }

    /** ROM PanelExpansion 回调（UI 线程，经 ElbowGlow post）。唯一可见性真源。 */
    void setPanelState(float fraction, boolean expanded) {
        float f = fraction < 0f ? 0f : (fraction > 1f ? 1f : fraction);
        if (expanded && f < 1f) f = 1f;          // 稳定展开态 → 锁定全亮
        if (Math.abs(f - panelFraction) < 0.004f) return;
        panelFraction = f;
        postInvalidateOnAnimation();
    }

    /**
     * 渲染线程每帧调用：过滤 + 计算每片门控 + 写缓冲。
     * 不做任何可见性决策（那是 setPanelState 的职责）。
     */
    void submit(float[] geo, int[] colors) {
        if (geo == null || colors == null) return;

        // 全量帧指纹：逐位相同 → 输出必然相同 → 零工作（G720 TBDR 功耗最优解）
        long h = 0x9E3779B97F4A7C15L;
        h = (h ^ colors.length) * 0x100000001B3L;
        final int lim = Math.min(colors.length * 42, geo.length);
        for (int i = 0; i < colors.length; i++) {
            h = (h ^ colors[i]) * 0x100000001B3L;
            final int b = i * 42;
            for (int k = 0; k < 42 && b + k < lim; k++) {
                h = (h ^ Float.floatToIntBits(geo[b + k])) * 0x100000001B3L;
            }
        }
        final boolean empty = colors.length == 0;
        synchronized (lock) {
            if (h == lastFrameHash && !empty) return;    // 静止帧：零工作
            lastFrameHash = h;
            if (empty) {
                if (writeCount != 0) postInvalidateOnAnimation();
                writeCount = 0;
                return;
            }
        }

        final boolean fTile = swTile, fSlider = swSlider, fCard = swCard, fCapsule = swCapsule;
        int w = 0;
        synchronized (lock) {
            for (int i = 0; i < colors.length && w < MAX; i++) {
                final int b = i * 42;
                if (b + 8 >= geo.length) break;
                final float x = geo[b], y = geo[b + 1];
                final float ww = geo[b + 2], hh = geo[b + 3];
                if (ww < 30f || hh < 30f || ww > 700f || hh > 700f) continue;
                final float mn = Math.min(ww, hh), mx = Math.max(ww, hh);
                boolean keep;
                if (mn > 300f) keep = fCard;
                else if (mx <= 260f) keep = fTile;
                else if (hh > ww) keep = fSlider;
                else keep = fCapsule;
                if (!keep) continue;
                float rr = geo[b + 8];
                final float half = mn * 0.5f;
                if (!(rr > 0f) || rr > half) rr = half;
                // 底色越亮加光越少（开态磁贴/白长条不被推白）
                final float gate = 1f - (((colors[i] >>> 24) & 0xFF) / 255f) * 0.45f;
                write[w * STRIDE] = x;
                write[w * STRIDE + 1] = y;
                write[w * STRIDE + 2] = ww;
                write[w * STRIDE + 3] = hh;
                write[w * STRIDE + 4] = rr;
                write[w * STRIDE + 5] = gate;
                w++;
            }
            writeCount = w;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (lift <= 0) return;
        if (pm != null && !pm.isInteractive()) return;   // 熄屏/DOZE：零绘制
        final float frac = panelFraction;
        if (frac <= 0.01f) return;                        // 面板收起：零绘制

        int n;
        synchronized (lock) {
            n = writeCount;
            if (n > 0) System.arraycopy(write, 0, draw, 0, n * STRIDE);
        }
        if (n <= 0) return;

        final int[] tc = TINTS[tint];
        final int tRGB = Color.rgb(tc[0], tc[1], tc[2]);
        final double aRad = Math.toRadians(angleDeg);
        final float lx = (float) Math.cos(aRad);
        final float ly = (float) -Math.sin(aRad);
        final float bandV = band;
        final float aBase = lift * frac;                  // ★ 面板进度直接乘进总幅度
        final float segW = bandV / SEG;

        float pmax = 0f;
        for (int j = 0; j < SEG; j++) {
            final float t = (j + 0.5f) / SEG;
            final float v = pulseSum(t);
            if (v > pmax) pmax = v;
        }

        for (int i = 0; i < n; i++) {
            final int o = i * STRIDE;
            final float x = draw[o], y = draw[o + 1], w = draw[o + 2], h = draw[o + 3];
            final float r = draw[o + 4], gate = draw[o + 5];

            final long sig = (Float.floatToIntBits(x) * 31 + Float.floatToIntBits(y)) * 31
                    + Float.floatToIntBits(w) * 31 + Float.floatToIntBits(h)
                    + Float.floatToIntBits(angleDeg) * 31 + tint;
            if (builtSig[i] != sig || builtFill[i] == null) {
                builtSig[i] = sig;
                final float cx = x + w * 0.5f, cy = y + h * 0.5f;
                final float ext = Math.max(w, h) * 0.5f;
                builtFill[i] = new LinearGradient(
                        cx - lx * ext, cy - ly * ext, cx + lx * ext, cy + ly * ext,
                        new int[]{dim(tRGB, 0.72f), dim(tRGB, 0.88f), tRGB},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
                SweepGradient sg = new SweepGradient(cx, cy,
                        new int[]{tRGB, dim(tRGB, 0.12f), tRGB, dim(tRGB, 0.12f), tRGB},
                        new float[]{0f, 0.25f, 0.5f, 0.75f, 1f});
                final Matrix m = new Matrix();
                m.postRotate(angleDeg, cx, cy);
                sg.setLocalMatrix(m);
                builtSweep[i] = sg;
            }

            // 1) 中心提亮（覆盖整片，保证"整块玻璃有光"）
            paintFill.setShader(builtFill[i]);
            paintFill.setAlpha((int) Math.min(255f, aBase * (inner / 100f) * gate));
            canvas.drawRoundRect(x + 1f, y + 1f, x + w - 1f, y + h - 1f,
                    Math.max(0f, r - 1f), Math.max(0f, r - 1f), paintFill);

            // 2) 边缘双峰光带（6 段递减环带，全部内缩 → 不外溢）
            for (int j = 0; j < SEG; j++) {
                final float t = (j + 0.5f) / SEG;
                final float inset = bandV * t;
                final float a = aBase * (edge / 100f) * (pulseSum(t) / pmax) * gate;
                if (a < 1f) continue;
                final Paint ps = paintSeg[j];
                ps.setStrokeWidth(segW + 0.5f);
                ps.setShader(builtSweep[i]);
                ps.setAlpha((int) Math.min(255f, a));
                final float rr = Math.max(0f, r - inset);
                canvas.drawRoundRect(x + inset, y + inset,
                        x + w - inset, y + h - inset, rr, rr, ps);
            }
        }
    }
}
