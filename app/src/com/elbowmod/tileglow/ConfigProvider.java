package com.elbowmod.tileglow;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 主配置通道：把模块应用自己的 prefs 通过 {@link #call} 暴露给 SystemUI 进程。
 * 设置页写入后调用 notifyChange 即时推送，SystemUI 侧另有 4 秒兜底轮询。
 * 只读、只暴露本模块自己的渲染参数，不含用户数据。
 */
public final class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.elbowmod.tileglow.config";
    public static final String METHOD_GET = "get";

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_GET.equals(method)) return new Bundle();
        Context ctx = getContext();
        SharedPreferences p = ctx == null
                ? null : ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        Bundle b = new Bundle();
        if (p == null) {
            b.putBoolean(Prefs.K_MASTER, Prefs.D_MASTER);
            b.putInt(Prefs.K_LIFT, Prefs.D_LIFT);
            b.putInt(Prefs.K_EDGE, Prefs.D_EDGE);
            b.putInt(Prefs.K_BAND, Prefs.D_BAND);
            b.putInt(Prefs.K_INNER, Prefs.D_INNER);
            b.putInt(Prefs.K_ANGLE, Prefs.D_ANGLE);
            b.putInt(Prefs.K_TINT, Prefs.D_TINT);
            b.putBoolean(Prefs.K_SW_TILE, Prefs.D_SW_TILE);
            b.putBoolean(Prefs.K_SW_SLIDER, Prefs.D_SW_SLIDER);
            b.putBoolean(Prefs.K_SW_CARD, Prefs.D_SW_CARD);
            b.putBoolean(Prefs.K_SW_CAPSULE, Prefs.D_SW_CAPSULE);
            return b;
        }
        b.putBoolean(Prefs.K_MASTER, p.getBoolean(Prefs.K_MASTER, Prefs.D_MASTER));
        b.putInt(Prefs.K_LIFT, p.getInt(Prefs.K_LIFT, Prefs.D_LIFT));
        b.putInt(Prefs.K_EDGE, p.getInt(Prefs.K_EDGE, Prefs.D_EDGE));
        b.putInt(Prefs.K_BAND, p.getInt(Prefs.K_BAND, Prefs.D_BAND));
        b.putInt(Prefs.K_INNER, p.getInt(Prefs.K_INNER, Prefs.D_INNER));
        b.putInt(Prefs.K_ANGLE, p.getInt(Prefs.K_ANGLE, Prefs.D_ANGLE));
        b.putInt(Prefs.K_TINT, p.getInt(Prefs.K_TINT, Prefs.D_TINT));
        b.putBoolean(Prefs.K_SW_TILE, p.getBoolean(Prefs.K_SW_TILE, Prefs.D_SW_TILE));
        b.putBoolean(Prefs.K_SW_SLIDER, p.getBoolean(Prefs.K_SW_SLIDER, Prefs.D_SW_SLIDER));
        b.putBoolean(Prefs.K_SW_CARD, p.getBoolean(Prefs.K_SW_CARD, Prefs.D_SW_CARD));
        b.putBoolean(Prefs.K_SW_CAPSULE, p.getBoolean(Prefs.K_SW_CAPSULE, Prefs.D_SW_CAPSULE));
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
