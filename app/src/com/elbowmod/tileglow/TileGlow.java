package com.elbowmod.tileglow;

import io.github.libxposed.api.XposedInterface;

/** 入口：只做一件事——把 Elbow 的玻璃帧整体提亮。不画任何像素。 */
public final class TileGlow {

    private static final String TAG = "TileGlow";
    private static volatile XposedInterface XI;

    private TileGlow() {
    }

    public static void install(XposedInterface xi, ClassLoader cl) {
        XI = xi;
        Config.attach(xi);
        ElbowGlow.install(xi, cl);
    }

    static void log(String msg) {
        try {
            android.util.Log.w(TAG, msg);
        } catch (Throwable ignored) {
        }
        try {
            XposedInterface xi = XI;
            if (xi != null) xi.log(4, TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    public static XposedInterface xi() {
        return XI;
    }
}
