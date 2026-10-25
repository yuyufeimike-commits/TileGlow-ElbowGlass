package com.elbowmod.tileglow;

import android.content.Context;
import android.content.SharedPreferences;

/** 参数键名与默认值的唯一来源。命名风格对齐 Elbow（percent / dp / enabled）。 */
public final class Prefs {

    public static final String FILE = "config";

    public static final String K_MASTER = "master_enabled";
    public static final boolean D_MASTER = true;

    /** 加光总幅度（0-160）：乘在整条光剖面上。 */
    public static final String K_LIFT = "lift_pct";
    public static final int D_LIFT = 90;

    /** 边缘光占比（0-100%）：双峰光带强度（AGSL-LiquidGlass edgePulse）。 */
    public static final String K_EDGE = "edge_pct";
    public static final int D_EDGE = 35;

    /** 边缘光带宽度（8-96 px）：贴边向内衰减的距离。 */
    public static final String K_BAND = "band_px";
    public static final int D_BAND = 26;

    /** 中心提亮占比（0-100%）：光带之外的内部均匀提亮。 */
    public static final String K_INNER = "inner_pct";
    public static final int D_INNER = 55;

    /** 光向角（0-360°，90=从正上方照）：影响受光面/背光面权重（Prismal）。 */
    public static final String K_ANGLE = "angle_deg";
    public static final int D_ANGLE = 90;

    /** 光色预设：0 冷白 #FCFDFF / 1 中性 #FFFFFF / 2 暖白 #FFF4E6。 */
    public static final String K_TINT = "tint_idx";
    public static final int D_TINT = 0;

    /** 四类控件分开关（按片的几何形状自动分类）。 */
    public static final String K_SW_TILE = "sw_tile";
    public static final boolean D_SW_TILE = true;
    public static final String K_SW_SLIDER = "sw_slider";
    public static final boolean D_SW_SLIDER = true;
    public static final String K_SW_CARD = "sw_card";
    public static final boolean D_SW_CARD = true;
    public static final String K_SW_CAPSULE = "sw_capsule";
    public static final boolean D_SW_CAPSULE = true;

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static void clearAll(SharedPreferences p) {
        try {
            p.edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
