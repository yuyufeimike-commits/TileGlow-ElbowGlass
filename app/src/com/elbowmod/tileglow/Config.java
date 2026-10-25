package com.elbowmod.tileglow;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 配置快照 —— SystemUI 侧唯一配置载体，热路径零 IPC。
 *
 * <h3>三通道（v6.4 定稿，真机日志实证的问题链）</h3>
 * <ol>
 *   <li><b>SystemUI 本地镜像</b>（开机即读）：每次配置更新都镜像到 SystemUI 自己的 prefs，
 *       重启后立即可用，不依赖任何跨应用调用；</li>
 *   <li><b>广播实时通道</b>：设置页每次改动发一条定向广播给 SystemUI 常驻进程 ——
 *       ColorOS 的 OplusAppStartupManager 拦得住「启动模块进程」，拦不住已运行进程的
 *       动态接收器（真机日志：prevent start ... by contentprovider / Failed to find
 *       provider info）；</li>
 *   <li><b>ContentProvider 轮询</b>（机会式）：应用进程存活时工作，死亡时被启动管理拦截，
 *       只作兜底。</li>
 * </ol>
 */
public final class Config {

    public static final String ACTION_CONFIG = "com.elbowmod.tileglow.CONFIG";
    static final String MIRROR = "config_mirror";

    private static final AtomicInteger REV = new AtomicInteger(0);
    private static final AtomicBoolean PROVIDER_OK = new AtomicBoolean(false);

    public static final class Snapshot {
        public final int rev;
        public final boolean master;
        public final int lift;      // 0-300
        public final int edge;      // 0-100
        public final int band;      // px 4-160
        public final int inner;     // 0-100
        public final float angleRad;
        public final int tint;      // 0-5
        public final boolean swTile, swSlider, swCard, swCapsule;

        Snapshot(int rev, boolean m, int lift, int edge, int band, int inner,
                 int angle, int tint, boolean t, boolean s, boolean c, boolean k) {
            this.rev = rev;
            this.master = m;
            this.lift = Prefs.clamp(lift, 0, 300);
            this.edge = Prefs.clamp(edge, 0, 200);
            this.band = Prefs.clamp(band, 4, 160);
            this.inner = Prefs.clamp(inner, 0, 200);
            this.angleRad = (float) Math.toRadians(Prefs.clamp(angle, 0, 360));
            this.tint = Prefs.clamp(tint, 0, 5);
            this.swTile = t;
            this.swSlider = s;
            this.swCard = c;
            this.swCapsule = k;
        }
    }

    public static final Snapshot DEFAULTS = new Snapshot(
            REV.incrementAndGet(), Prefs.D_MASTER, Prefs.D_LIFT, Prefs.D_EDGE, Prefs.D_BAND,
            Prefs.D_INNER, Prefs.D_ANGLE, Prefs.D_TINT,
            Prefs.D_SW_TILE, Prefs.D_SW_SLIDER, Prefs.D_SW_CARD, Prefs.D_SW_CAPSULE);

    /** 热路径唯一入口：一次 volatile 读。 */
    public static volatile Snapshot CUR = DEFAULTS;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile Context appCtx;
    private static volatile Handler bgHandler;
    private static volatile SharedPreferences mirror;

    private Config() {
    }

    public static boolean attach(XposedInterface xi) {
        if (!STARTED.compareAndSet(false, true)) return ready();
        // 根治通道：RemotePreferences = LSPosed 自有存储，注入第一帧即可读；
        // 设置页经 LSPosed 服务写入，重启后初值即用户配置，不再有"开机一段时间不对"。
        attachRemotePrefs(xi);
        boolean ok = attachAppPrefs();
        if (!ok) scheduleAttachRetry();
        TileGlow.log("config attached provider=" + PROVIDER_OK.get());
        return ok;
    }

    private static boolean attachAppPrefs() {
        try {
            if (PROVIDER_OK.get()) return true;
            Context ctx = Refl.appContext();
            if (ctx == null) return false;
            appCtx = ctx;

            // ① 本地镜像：重启后立即可用的 last-known 配置（无跨应用依赖）
            mirror = ctx.getSharedPreferences(MIRROR, Context.MODE_PRIVATE);
            loadMirror();

            // ② 广播实时通道（动态注册；SystemUI 常驻，启动管理拦不住）
            final IntentFilter f = new IntentFilter(ACTION_CONFIG);
            final BroadcastReceiver rx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        apply(i.getExtras());
                    } catch (Throwable ignored) {
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(rx, f);
            }

            // ③ Provider 兜底轮询（应用进程存活时工作）
            if (bgHandler == null) {
                final Uri uri = ConfigProvider.uri();
                ctx.getContentResolver().registerContentObserver(uri, false,
                        new ContentObserver(new Handler(Looper.getMainLooper())) {
                            @Override
                            public void onChange(boolean selfChange) {
                                scheduleReload();
                            }
                        });
                HandlerThread th = new HandlerThread("TileGlowCfg");
                th.start();
                final Handler h = new Handler(th.getLooper());
                bgHandler = h;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Bundle b = appCtx.getContentResolver()
                                    .call(ConfigProvider.uri(), ConfigProvider.METHOD_GET, null, null);
                            if (b != null) apply(b);
                        } catch (Throwable ignored) {
                        }
                        h.postDelayed(this, 4000L);
                    }
                });
            }
            PROVIDER_OK.set(true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void scheduleAttachRetry() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (PROVIDER_OK.get()) return;
                if (attachAppPrefs()) {
                    TileGlow.log("config provider attached (retry)");
                    return;
                }
                new Handler(Looper.getMainLooper()).postDelayed(this, 3000L);
            }
        }, 2000L);
    }

    private static void scheduleReload() {
        final Handler h = bgHandler;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = appCtx;
                    if (c == null) return;
                    Bundle b = c.getContentResolver()
                            .call(ConfigProvider.uri(), ConfigProvider.METHOD_GET, null, null);
                    if (b != null) apply(b);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** ② 广播 / ③ Provider 共用的落地点：更新 CUR 并镜像到本地。 */
    private static void apply(Bundle b) {
        if (b == null) return;
        try {
            CUR = new Snapshot(
                    REV.incrementAndGet(),
                    b.getBoolean(Prefs.K_MASTER, Prefs.D_MASTER),
                    b.getInt(Prefs.K_LIFT, Prefs.D_LIFT),
                    b.getInt(Prefs.K_EDGE, Prefs.D_EDGE),
                    b.getInt(Prefs.K_BAND, Prefs.D_BAND),
                    b.getInt(Prefs.K_INNER, Prefs.D_INNER),
                    b.getInt(Prefs.K_ANGLE, Prefs.D_ANGLE),
                    b.getInt(Prefs.K_TINT, Prefs.D_TINT),
                    b.getBoolean(Prefs.K_SW_TILE, Prefs.D_SW_TILE),
                    b.getBoolean(Prefs.K_SW_SLIDER, Prefs.D_SW_SLIDER),
                    b.getBoolean(Prefs.K_SW_CARD, Prefs.D_SW_CARD),
                    b.getBoolean(Prefs.K_SW_CAPSULE, Prefs.D_SW_CAPSULE));
            saveMirror();
        } catch (Throwable ignored) {
        }
    }

    /** 镜像到 SystemUI 自己的 prefs：重启后的 last-known 配置。 */
    private static void saveMirror() {
        try {
            final SharedPreferences m = mirror;
            if (m == null) return;
            final Snapshot s = CUR;
            m.edit()
                    .putBoolean(Prefs.K_MASTER, s.master)
                    .putInt(Prefs.K_LIFT, s.lift)
                    .putInt(Prefs.K_EDGE, s.edge)
                    .putInt(Prefs.K_BAND, s.band)
                    .putInt(Prefs.K_INNER, s.inner)
                    .putInt(Prefs.K_ANGLE, (int) Math.toDegrees(s.angleRad))
                    .putInt(Prefs.K_TINT, s.tint)
                    .putBoolean(Prefs.K_SW_TILE, s.swTile)
                    .putBoolean(Prefs.K_SW_SLIDER, s.swSlider)
                    .putBoolean(Prefs.K_SW_CARD, s.swCard)
                    .putBoolean(Prefs.K_SW_CAPSULE, s.swCapsule)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static void loadMirror() {
        try {
            final SharedPreferences m = mirror;
            if (m == null || m.getAll().isEmpty()) return;   // 无镜像 → 保持默认
            CUR = new Snapshot(
                    REV.incrementAndGet(),
                    m.getBoolean(Prefs.K_MASTER, Prefs.D_MASTER),
                    m.getInt(Prefs.K_LIFT, Prefs.D_LIFT),
                    m.getInt(Prefs.K_EDGE, Prefs.D_EDGE),
                    m.getInt(Prefs.K_BAND, Prefs.D_BAND),
                    m.getInt(Prefs.K_INNER, Prefs.D_INNER),
                    m.getInt(Prefs.K_ANGLE, Prefs.D_ANGLE),
                    m.getInt(Prefs.K_TINT, Prefs.D_TINT),
                    m.getBoolean(Prefs.K_SW_TILE, Prefs.D_SW_TILE),
                    m.getBoolean(Prefs.K_SW_SLIDER, Prefs.D_SW_SLIDER),
                    m.getBoolean(Prefs.K_SW_CARD, Prefs.D_SW_CARD),
                    m.getBoolean(Prefs.K_SW_CAPSULE, Prefs.D_SW_CAPSULE));
        } catch (Throwable ignored) {
        }
    }

    private static void reload(SharedPreferences p) {
        if (p == null) return;
        try {
            CUR = new Snapshot(
                    REV.incrementAndGet(),
                    p.getBoolean(Prefs.K_MASTER, Prefs.D_MASTER),
                    p.getInt(Prefs.K_LIFT, Prefs.D_LIFT),
                    p.getInt(Prefs.K_EDGE, Prefs.D_EDGE),
                    p.getInt(Prefs.K_BAND, Prefs.D_BAND),
                    p.getInt(Prefs.K_INNER, Prefs.D_INNER),
                    p.getInt(Prefs.K_ANGLE, Prefs.D_ANGLE),
                    p.getInt(Prefs.K_TINT, Prefs.D_TINT),
                    p.getBoolean(Prefs.K_SW_TILE, Prefs.D_SW_TILE),
                    p.getBoolean(Prefs.K_SW_SLIDER, Prefs.D_SW_SLIDER),
                    p.getBoolean(Prefs.K_SW_CARD, Prefs.D_SW_CARD),
                    p.getBoolean(Prefs.K_SW_CAPSULE, Prefs.D_SW_CAPSULE));
        } catch (Throwable ignored) {
        }
    }

    /** 备通道：libXposed RemotePreferences（仅当拿不到 Context 时兜底）。 */
    private static boolean attachRemotePrefs(XposedInterface xi) {
        try {
            final SharedPreferences p = xi.getRemotePreferences(Prefs.FILE);
            if (p == null) return false;
            reload(p);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean ready() {
        return appCtx != null;
    }

    static final class Refl {
        static Context appContext() {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Method cur = at.getDeclaredMethod("currentApplication");
                cur.setAccessible(true);
                Object app = cur.invoke(null);
                if (app instanceof Context) return (Context) app;
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
