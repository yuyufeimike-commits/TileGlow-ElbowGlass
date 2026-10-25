package com.elbowmod.tileglow;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * 在 Elbow 的液态玻璃层内做「整块加色提亮」。
 *
 * <h3>链路</h3>
 * <pre>
 * View#onAttachedToWindow（过滤 TextureView + Elbow ClassLoader）
 *   └─ bind()：用 Elbow 的 loader 装载 NativeGlassBridge，钩 nativeSubmitFrame
 *        └─ 每帧：把 geometry 交给 GlowLayerView（加色层）
 *   └─ scheduleAttach()：把加色层插到玻璃 TextureView 的下一个兄弟位置
 * </pre>
 *
 * <h3>为什么是"加色层"而不是改帧数据</h3>
 * 真机实测（v3.x~v4.4）：{@code colors[]} 在 QS 路径不参与合成、材质 10 参数里没有亮度项、
 * 每帧标量均无效。唯一有效且零冲突的位置是：玻璃已画完、内容未画之前的那一层，
 * 用 {@code BlendMode.PLUS} 只叠加光通量 —— 透明处加 0 仍透明，物理上不可能变成"白圈"。
 *
 * <h3>跨模块 ClassLoader</h3>
 * LSPosed 每模块独立 ClassLoader，直接 Class.forName 找不到 Elbow 的类。
 * 先钩框架类 {@code TextureView}，从活实例取 {@code getClassLoader()} 再延迟安装钩子。
 */
final class ElbowGlow {

    private static final String BRIDGE =
            "com.treatangus.elbowhitcolor.liquidglass.renderer.native.NativeGlassBridge";

    private static volatile boolean bound = false;
    /** 最近的 Elbow 玻璃 TextureView（重挂看门狗用）。 */
    private static volatile java.lang.ref.WeakReference<android.view.View> lastTv;
    /** 玻璃体内的整块加色层（挂在与玻璃层相同的父容器里）。 */
    private static volatile GlowLayerView glowView;

    private ElbowGlow() {
    }

    static void install(final XposedInterface xi, ClassLoader cl) {
        try {
            final Class<?> view = Class.forName("android.view.View", false, cl);
            final Class<?> textureView = Class.forName("android.view.TextureView", false, cl);
            Method m = view.getDeclaredMethod("onAttachedToWindow");
            xi.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    final Object r = chain.proceed();
                    try {
                        final Object self = chain.getThisObject();
                        if (self != null && textureView.isInstance(self)) {
                            final ClassLoader l = self.getClass().getClassLoader();
                            // 只处理 Elbow 自己的 TextureView 子类，不碰 SystemUI 本体的
                            if (l != null && l != ElbowGlow.class.getClassLoader()) {
                                lastTv = new java.lang.ref.WeakReference<>((android.view.View) self);
                                if (!bound) bind(xi, self);
                                else scheduleAttach(self);   // 后续再挂进来的玻璃层也接管
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
                }
            });
            TileGlow.log("hook View#onAttachedToWindow(TextureView filter) OK");
        } catch (Throwable t) {
            TileGlow.log("hook TextureView failed: " + t);
        }
        hookPanelExpansion(xi, cl);
    }

    /**
     * 挂 ColorOS 面板展开度回调（收起白圈残留的机制性修复）。
     * ROM 实证：com.oplus.systemui.shade.OplusShadeExpansionStateManagerExImpl
     *          .onPanelExpansionChanged(...) 携带 fraction/expanded/tracking。
     * 加色层 alpha 严格乘以 fraction → 面板收到哪，光就淡到哪。
     */
    private static void hookPanelExpansion(final XposedInterface xi, ClassLoader cl) {
        try {
            Class<?> impl = Class.forName(
                    "com.oplus.systemui.shade.OplusShadeExpansionStateManagerExImpl", false, cl);
            Method target = null;
            for (Method m : impl.getDeclaredMethods()) {
                if ("onPanelExpansionChanged".equals(m.getName())) { target = m; break; }
            }
            if (target == null) {
                TileGlow.log("PANEL hook skipped: no onPanelExpansionChanged");
                return;
            }
            xi.hook(target).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(new Hooker() {
                private float last = -1f;
                private boolean lastExp = true;
                private final android.os.Handler ui =
                        new android.os.Handler(android.os.Looper.getMainLooper());

                @Override
                public Object intercept(Chain chain) throws Throwable {
                    try {
                        float f = Float.NaN;
                        boolean expanded = lastExp;
                        for (Object a : chain.getArgs()) {
                            if (a instanceof Float && Float.isNaN(f)) {
                                final float v = (Float) a;
                                if (v >= 0f && v <= 1f) f = v;
                            } else if (a instanceof Boolean) {
                                expanded = (Boolean) a;
                            }
                        }
                        if (!Float.isNaN(f)
                                && (Math.abs(f - last) > 0.04f || expanded != lastExp)) {
                            last = f;
                            lastExp = expanded;
                            final float ff = f;
                            final boolean ex = expanded;
                            final GlowLayerView g = glowView;
                            if (g != null) {
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        g.setPanelState(ff, ex);   // 单写者：只在 UI 线程改状态
                                    }
                                });
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            TileGlow.log("PANEL hook OK " + target.getName());
        } catch (Throwable t) {
            TileGlow.log("PANEL hook failed: " + t);
        }
    }

    /** 拿到 Elbow 的 ClassLoader → 装 nativeSubmitFrame 钩子。只生效一次。 */
    private static synchronized void bind(XposedInterface xi, Object inst) {
        if (bound) return;
        final ClassLoader l = inst.getClass().getClassLoader();
        if (l == null || l == ElbowGlow.class.getClassLoader()) return;

        scheduleAttach(inst);

        try {
            final Class<?> bridge = Class.forName(BRIDGE, false, l);
            final Class<?>[] sig = {
                    long.class, float[].class, int[].class, boolean.class,
                    float.class, float.class, float.class, float.class, float.class,
                    int.class, boolean.class, float.class, boolean.class,
            };
            Method sub = bridge.getDeclaredMethod("nativeSubmitFrame", sig);
            xi.hook(sub).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    try {
                        if (Config.CUR.master) {
                            final GlowLayerView g = glowView;
                            if (g != null) {
                                g.apply(Config.CUR);
                                g.submit((float[]) chain.getArg(1), (int[]) chain.getArg(2));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            bound = true;
            TileGlow.log("hook nativeSubmitFrame OK, glow layer pending attach");
        } catch (Throwable t) {
            TileGlow.log("bind bridge failed: " + t);
        }
    }

    /**
     * 把加色层插到 Elbow 玻璃 TextureView 的<b>下一个兄弟位置</b>。
     *
     * <p>真机实测：玻璃层是 {@code NotificationShadeWindowView} 的 index 6（共 23 个子节点），
     * {@code index+1} = 玻璃之上、控制中心内容之下 —— 正是"光在玻璃里"该在的层。
     * 必须 {@code post}：在别的 View 的 onAttachedToWindow 里直接 addView 会撞上
     * 「layout 期间 requestLayout」。
     */
    /** 单一 1s 看门狗（合并配置轮询与重挂检查，减少常驻定时器唤醒）。 */
    private static void startWatchdog() {
        final android.os.Handler wd = new android.os.Handler(android.os.Looper.getMainLooper());
        wd.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    final GlowLayerView g = glowView;
                    final android.view.View tv = lastTv == null ? null : lastTv.get();
                    if (tv != null && (g == null || !g.isAttachedToWindow())) {
                        scheduleAttach(tv);
                    } else if (g != null) {
                        g.apply(Config.CUR);   // 面板静止、Elbow 不提交帧时也能改参数
                    }
                } catch (Throwable ignored) {
                }
                wd.postDelayed(this, 1000L);
            }
        }, 1000L);
    }

    private static void scheduleAttach(final Object tvObj) {
        if (!(tvObj instanceof android.view.View)) return;
        final android.view.View tv = (android.view.View) tvObj;
        tv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // 不做宽度门槛：开机早期 onAttachedToWindow 时布局未完成 width=0，
                    // 一旦在这里 return，之后面板打开也不会再有 attach 事件 → 加色层永不创建（v5.6 实测踩坑）
                    final android.view.ViewParent vp = tv.getParent();
                    if (!(vp instanceof android.view.ViewGroup)) return;
                    final android.view.ViewGroup parent = (android.view.ViewGroup) vp;
                    final GlowLayerView cur = glowView;
                    if (cur != null && cur.isAttachedToWindow()) return;        // 仍在窗口里 → 有效
                    if (cur != null && cur.getParent() instanceof android.view.ViewGroup) {
                        // 面板重建后旧壳已脱离窗口（真机实测：收起再展开后整层失画）→ 摘下来重挂
                        ((android.view.ViewGroup) cur.getParent()).removeView(cur);
                    }
                    final GlowLayerView g = new GlowLayerView(tv.getContext());
                    g.apply(Config.CUR);
                    final int idx = Math.min(parent.indexOfChild(tv) + 1, parent.getChildCount());
                    parent.addView(g, idx, new android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                    glowView = g;
                    startWatchdog();       // 单一 1s 看门狗：重挂检查 + 配置同步
                    TileGlow.log("GLOW attached idx=" + idx + " parent=" + parent.getClass().getName());
                } catch (Throwable t) {
                    TileGlow.log("GLOW attach failed: " + t);
                }
            }
        });
    }
}
