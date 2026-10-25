package com.elbowmod.tileglow;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 独立 LSPosed 模块入口（{@code META-INF/xposed/java_init.list} 指定此类）。
 *
 * <p>职责保持极薄：只做包名过滤与委派。
 * 任何实际逻辑都不放在这里 —— 旧版本把入口和业务混在一起，
 * 加上 v1~v11 十一次改版都在这个文件附近打转，导致无法回归验证。
 */
public final class Module extends XposedModule {

    private static final String TAG = "TileGlow";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        // 只记录，不做安装（此时进程内还没有目标 ClassLoader）
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            XposedInterface xi = this;
            if (!"com.android.systemui".equals(param.getPackageName())) {
                return;
            }
            TileGlow.install(xi, param.getClassLoader());
        } catch (Throwable t) {
            try {
                this.log(4, TAG, "onPackageReady failed: " + t);
            } catch (Throwable ignored) {
            }
        }
    }
}