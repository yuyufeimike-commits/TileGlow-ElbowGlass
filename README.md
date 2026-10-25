# TileGlow — Elbow 液态玻璃提亮模块

一个 LSPosed 模块：在 ColorOS 16 的 Elbow 液态玻璃渲染层内，为控制中心磁贴/滑条/卡片
做**整块加色提亮**。光不是画在玻璃上，而是玻璃体内多出来的光。

**机型适配**：OnePlus PLF110（天玑 9300 / Immortalis-G720 / 1080×2392 OLED / 120Hz），
ColorOS 16.0.10.501，Elbow 43,668,002 B。其他机型/版本需自行验证。

---

## 成品

`release/TileGlow-v9.0.apk` — 直接 `adb install -r` 安装，LSPosed 勾选并作用于
`com.android.systemui`，**整机重启**（killall 不可靠）。

## 它是怎么工作的（一句话版）

```
Elbow 每帧提交玻璃几何（nativeSubmitFrame: 42 float/片 + 1 int 色/片）
        ↓ 模块钩住这一帧，拿到每片玻璃的 x/y/w/h/圆角
在自己的一层视图里用 BlendMode.PLUS 画整块加色光（SDF 形状 + 双峰光带 + 双侧边缘光）
        ↓ 该视图插在玻璃 TextureView 的下一个兄弟位（玻璃之上、图标之下）
输出 = 玻璃 + 光。透明处加 0 仍透明 —— 物理上不可能变成"黑遮罩上的白圈"
```

面板收起时通过 ROM 的 `OplusShadeExpansionStateManagerExImpl.onPanelExpansionChanged`
拿展开度（1=展开 0=收起），加色 alpha 严格乘以它 → **光与面板同生同灭，无残留**。

## 注意事项（踩过的坑，务必读完）

1. **装完模块必须整机重启**。`killall systemui` 后 LSPosed 经常不重新注入（试错记录 B9，
   v5.8/v8.0 两次实测踩坑）。
2. **必须用设备上实际安装的 Elbow APK 做逆向**。仓库版本与设备版本差 1 字节混淆名就全挪位。
3. **Hook 的 Elbow 类名是阿拉伯附加符号混淆**（如 `l.\u06e2\u06df`），必须用真实码点书写，
   手打字符会被 Unicode 规范化成别的类，且 `Class.forName` 不报错、只在找方法时静默失败。
4. **Java 注释里不能出现 `\u`**——词法分析在注释之前处理，会编译失败。
5. **Git Bash 下 adb 路径**：所有 adb 命令前 `export MSYS_NO_PATHCONV=1`。
6. **BlendMode.PLUS 对纯色/渐变画笔有效，但挂 RuntimeShader 的画笔上不生效**
   （v5.2/v5.3 实测黑框）——本模块因此放弃 AGSL，全部纯 Canvas。
7. **不要改 `colors[]`**：实测它在 QS 路径不参与合成（改了零像素变化），且 alpha
   一动玻璃变奶白。
8. **收缩环残留**：必须挂 ROM 的面板展开度回调（fraction 1=展开 0=收起），
   加色 alpha ×= fraction，≤0.01 一帧不画。
9. **熄屏缩小环**：熄屏动画中 Elbow 仍提交收缩几何，且会误触发展开态自证 →
   submit 入口必须加 `PowerManager.isInteractive()` 拦截。
10. **锁屏闪烁**：锁屏场景 Elbow 间歇提交空帧 → 需要 250ms 空帧去抖，不能立即清空。
11. **调参不生效**（v5.x 最大坑）：ColorOS 的 OplusAppStartupManager 会拦截 SystemUI
    启动模块进程 → Provider 通道失联。v9.0 用 LSPosed 官方服务通道根治：
    设置页 → XposedServiceHelper 绑定 → 写 RemotePreferences（LSPosed 自有存储）→
    SystemUI 注入第一帧即可读。**装完 v9.0 后打开一次设置页**（自动同步当前配置到
    LSPosed 存储），此后任何重启配置不丢。
12. **性能**：G720 是 TBDR 架构，功耗=带宽+混合次数。静止帧指纹跳过（FNV-1a 全量
    64 位）+ 热路径零分配。**不要用弱签名去重**（对称动画碰撞 → 白圈飘逸，v6.6 翻车）。

## 参数（设置页，≤4 秒生效）

| 参数 | 范围 | 说明 |
|---|---|---|
| 加光幅度 | 0-300 | 总强度 |
| 边缘光占比 | 0-200% | 双峰光带（贴边窄峰+宽峰），>100 过驱 |
| 中心提亮占比 | 0-200% | 内部均匀提亮 |
| 光带宽度 | 4-160px | 玻璃厚度感 |
| 光向角 | 0-360° | 90=正上方 |
| 光色 | 6 种 | 冷白/中性/暖白/天空/玫瑰/香槟 |
| 分开关 | 4 个 | 磁贴/竖滑条/卡片/横胶囊 |

## 构建

```bash
cd app/tools
python build.py        # 需 D:\android-toolchain（jdk + build-tools 35 + android-35）
```

依赖：`libxposed-api.jar`（LSPosed 注入时提供运行时）、`libxposed-service.jar`
（Maven `io.github.libxposed:service:102.0.0`，接口类随模块打包）。

## 已知限制

- 仅适配 PLF110 + 该版本 Elbow；其他机型需重走"设备 Elbow APK 逆向"流程。
- 亮度滑条白色填充接近 255 处，加色无可见效果（物理上限）。
- 2x2 若为 squircle，`drawRoundRect` 与真轮廓有细微残差（半径已是真值）。
- 面板展开度回调丢失时（v7.x 曾发生），展开态自证兜底。

## 回滚

```bash
adb uninstall com.elbowmod.tileglow && adb reboot
```
