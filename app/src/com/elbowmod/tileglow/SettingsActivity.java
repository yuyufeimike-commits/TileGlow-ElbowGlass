package com.elbowmod.tileglow;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面。参数对齐 Elbow 的命名风格：强度/占比用 %，光带用 px，光向用角度。
 * 每次写入都尝试 notifyChange，SystemUI 侧立即重载（另有 4 秒兜底轮询）。
 */
public final class SettingsActivity extends Activity {

    private SharedPreferences p;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = Prefs.get(this);
        io.github.libxposed.service.XposedServiceHelper.registerListener(
                new io.github.libxposed.service.XposedServiceHelper.OnServiceListener() {
                    @Override
                    public void onServiceBind(io.github.libxposed.service.XposedService s) {
                        svc = s;
                        pushRemote();                 // 绑定即同步一次，根治开机初值
                    }

                    @Override
                    public void onServiceDied(io.github.libxposed.service.XposedService s) {
                        svc = null;
                    }
                });
        setContentView(build());
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static io.github.libxposed.service.XposedService svc;

    private void pushRemote() {
        final io.github.libxposed.service.XposedService sv = svc;
        if (sv == null) return;                       // 服务未绑定 → 走广播/镜像通道
        final SharedPreferences sp = p;
        try {
            android.content.SharedPreferences rp = sv.getRemotePreferences("config");
            rp.edit()
              .putBoolean(Prefs.K_MASTER, sp.getBoolean(Prefs.K_MASTER, Prefs.D_MASTER))
              .putInt(Prefs.K_LIFT, sp.getInt(Prefs.K_LIFT, Prefs.D_LIFT))
              .putInt(Prefs.K_EDGE, sp.getInt(Prefs.K_EDGE, Prefs.D_EDGE))
              .putInt(Prefs.K_BAND, sp.getInt(Prefs.K_BAND, Prefs.D_BAND))
              .putInt(Prefs.K_INNER, sp.getInt(Prefs.K_INNER, Prefs.D_INNER))
              .putInt(Prefs.K_ANGLE, sp.getInt(Prefs.K_ANGLE, Prefs.D_ANGLE))
              .putInt(Prefs.K_TINT, sp.getInt(Prefs.K_TINT, Prefs.D_TINT))
              .putBoolean(Prefs.K_SW_TILE, sp.getBoolean(Prefs.K_SW_TILE, Prefs.D_SW_TILE))
              .putBoolean(Prefs.K_SW_SLIDER, sp.getBoolean(Prefs.K_SW_SLIDER, Prefs.D_SW_SLIDER))
              .putBoolean(Prefs.K_SW_CARD, sp.getBoolean(Prefs.K_SW_CARD, Prefs.D_SW_CARD))
              .putBoolean(Prefs.K_SW_CAPSULE, sp.getBoolean(Prefs.K_SW_CAPSULE, Prefs.D_SW_CAPSULE))
              .apply();
        } catch (Throwable ignored) {
        }
    }

    private void changed() {
        final SharedPreferences sp = p;
        pushRemote();
        // ① 定向广播给 SystemUI 常驻进程：实时生效（ColorOS 拦不住已运行进程的接收器）
        try {
            Intent i = new Intent(Config.ACTION_CONFIG);
            i.setPackage("com.android.systemui");
            i.putExtra(Prefs.K_MASTER, sp.getBoolean(Prefs.K_MASTER, Prefs.D_MASTER));
            i.putExtra(Prefs.K_LIFT, sp.getInt(Prefs.K_LIFT, Prefs.D_LIFT));
            i.putExtra(Prefs.K_EDGE, sp.getInt(Prefs.K_EDGE, Prefs.D_EDGE));
            i.putExtra(Prefs.K_BAND, sp.getInt(Prefs.K_BAND, Prefs.D_BAND));
            i.putExtra(Prefs.K_INNER, sp.getInt(Prefs.K_INNER, Prefs.D_INNER));
            i.putExtra(Prefs.K_ANGLE, sp.getInt(Prefs.K_ANGLE, Prefs.D_ANGLE));
            i.putExtra(Prefs.K_TINT, sp.getInt(Prefs.K_TINT, Prefs.D_TINT));
            i.putExtra(Prefs.K_SW_TILE, sp.getBoolean(Prefs.K_SW_TILE, Prefs.D_SW_TILE));
            i.putExtra(Prefs.K_SW_SLIDER, sp.getBoolean(Prefs.K_SW_SLIDER, Prefs.D_SW_SLIDER));
            i.putExtra(Prefs.K_SW_CARD, sp.getBoolean(Prefs.K_SW_CARD, Prefs.D_SW_CARD));
            i.putExtra(Prefs.K_SW_CAPSULE, sp.getBoolean(Prefs.K_SW_CAPSULE, Prefs.D_SW_CAPSULE));
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
        // ② Provider 兜底通道的刷新通知
        try {
            ContentResolver cr = getContentResolver();
            cr.notifyChange(ConfigProvider.uri(), null);
        } catch (Throwable ignored) {
        }
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, dp(28));

        TextView title = txt("磁贴发光 v5", 22, Color.BLACK);
        root.addView(title);
        TextView sub = txt("在 Elbow 液态玻璃层内做整块加色提亮（AGSL：SDF + 双峰光带 + 双侧边缘光）。"
                + "形状/位置/圆角全部来自 Elbow 每帧几何，只叠加光，不改材质不改背景。", 12, 0xFF888888);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        section(root, "开关");
        addSwitch(root, "启用模块", Prefs.K_MASTER, Prefs.D_MASTER);

        section(root, "光");
        addSlider(root, "加光幅度", Prefs.K_LIFT, Prefs.D_LIFT, 0, 300, "");
        addSlider(root, "边缘光占比", Prefs.K_EDGE, Prefs.D_EDGE, 0, 200, "%");
        addSlider(root, "中心提亮占比", Prefs.K_INNER, Prefs.D_INNER, 0, 200, "%");
        addSlider(root, "光带宽度", Prefs.K_BAND, Prefs.D_BAND, 4, 160, "px");
        addSlider(root, "光向角", Prefs.K_ANGLE, Prefs.D_ANGLE, 0, 360, "°（90=正上方）");
        addTint(root);

        section(root, "作用范围（按控件形状自动分类）");
        addSwitch(root, "方形磁贴（1x1 / 2x2）", Prefs.K_SW_TILE, Prefs.D_SW_TILE);
        addSwitch(root, "竖滑条（亮度 / 音量）", Prefs.K_SW_SLIDER, Prefs.D_SW_SLIDER);
        addSwitch(root, "卡片（媒体 / 设备）", Prefs.K_SW_CARD, Prefs.D_SW_CARD);
        addSwitch(root, "横胶囊（顶部快捷）", Prefs.K_SW_CAPSULE, Prefs.D_SW_CAPSULE);

        Button reset = new Button(this);
        reset.setText("恢复默认");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.clearAll(p);
                changed();
                Toast.makeText(SettingsActivity.this, "已恢复默认", Toast.LENGTH_SHORT).show();
                recreate();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(18);
        root.addView(reset, lp);

        TextView note = txt("改动即时生效（≤4 秒）：拉下控制中心即可看到。", 11, 0xFF999999);
        note.setPadding(0, dp(10), 0, 0);
        root.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        return sv;
    }

    private void addTint(LinearLayout root) {
        TextView head = txt("光色", 13, 0xFF333333);
        head.setPadding(0, dp(10), 0, 0);
        root.addView(head);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final String[] names = {"冷白", "中性", "暖白", "天空", "玫瑰", "香槟"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    p.edit().putInt(Prefs.K_TINT, idx).apply();
                    changed();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            row.addView(b, lp);
        }
        root.addView(row);
    }

    private void section(LinearLayout root, String name) {
        TextView t = txt(name, 13, 0xFF3C6DF0);
        t.setPadding(0, dp(16), 0, dp(4));
        root.addView(t);
    }

    private TextView txt(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private void addSwitch(LinearLayout root, String label, final String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sw.setChecked(p.getBoolean(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                p.edit().putBoolean(key, checked).apply();
                changed();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        root.addView(sw, lp);
    }

    private void addSlider(LinearLayout root, final String label, final String key, int def,
                           final int min, final int max, final String unit) {
        final TextView head = txt(label + "：" + p.getInt(key, def) + unit, 13, 0xFF333333);
        head.setPadding(0, dp(10), 0, 0);
        root.addView(head);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Prefs.clamp(p.getInt(key, def), min, max) - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int v = min + progress;
                head.setText(label + "：" + v + unit);
                if (fromUser) {
                    p.edit().putInt(key, v).apply();
                    changed();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        root.addView(sb);
    }
}
