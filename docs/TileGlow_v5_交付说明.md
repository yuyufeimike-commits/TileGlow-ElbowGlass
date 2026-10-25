# TileGlow v6.7 交付说明（Elbow 玻璃体提亮）

> 实现方式：**在 Elbow 玻璃 TextureView 的下一个兄弟位置插入一层加色视图**，
> 形状/位置/圆角全部取自 Elbow 每帧提交的 `nativeSubmitFrame` 几何数据，
> 用 `BlendMode.PLUS`（加色）整块填充 —— 只叠加光通量，不覆盖、不描边、不改 Elbow 任何状态。
> 视觉判定权在你；本文只给客观测量与操作方法。

---

## 一、装机

```bash
adb install -r D:\workbuddy\2026-10-25-09-27-16\_mod\build\TileGlow-v6.7.apk
# 然后整机重启（killall systemui 不可靠，见交接包 B9）
adb reboot
```

模块作用域：`com.android.systemui`（已在 meta-inf 声明）。

## 二、调参

| 参数 | 键 | 范围 | 说明 |
|---|---|---|---|
| 参数 | 键 | 范围 | 说明 |
|---|---|---|---|
| 总开关 | `master_enabled` | bool | 关掉后零绘制、零 hook 效果 |
| 加光幅度 | `lift_pct` | 0-300 | 总强度；默认 90（实测 260 档环带 251.8） |
| 边缘光占比 | `edge_pct` | 0-200 | 双峰光带强度（贴边窄峰+宽峰），>100 为过驱 |
| 中心提亮占比 | `inner_pct` | 0-200 | 内部均匀提亮，>100 为过驱（255 处饱和） |
| 光带宽度 | `band_px` | 4-160 | 边缘带宽度（px），对应玻璃厚度感 |
| 光向角 | `angle_deg` | 0-360 | 90=正上方；影响受光端渐变与扫掠方位 |
| 光色 | `tint_idx` | 0-5 | 冷白/中性/暖白/天空/玫瑰/香槟 |
| 方形磁贴 | `sw_tile` | bool | 1x1/2x2 分开关 |
| 竖滑条 | `sw_slider` | bool | 亮度/音量分开关 |
| 卡片 | `sw_card` | bool | 媒体/设备卡分开关 |
| 横胶囊 | `sw_capsule` | bool | 顶部快捷胶囊分开关 |

改动方式：模块设置页（`com.elbowmod.tileglow`），≤4 秒生效，无需重启
（配置带版本号 rev，加色层 1 秒轮询 rev 变化即主动重绘 —— 面板静止时也能改参数）。

**v5.5/v5.6 细腻化与稳定性**：
- 边缘光带从 2 道硬边 STROKE 改为 **6 段递减环带**（alpha 按 AGSL 双峰函数取值），无台阶；
- 默认带宽 48→26px、edge 40→35、inner 60→55（默认观感更细）；
- 已有底色越亮加光越少（k = 1 − a/255×0.45），开态磁贴/白长条不会被推白；
- 修复「收起再展开后整层失画」：面板重建会换新玻璃层，挂载逻辑改为
  `isAttachedToWindow()` 判活 + 失效摘除重挂（真机 3 轮开关验证：0 失败 0 崩溃）。

**v5.4 渲染实现**：纯 Canvas + BlendMode.PLUS 纯色/渐变画笔（无 shader）。
AGSL 路线在 v5.2/v5.3 被真机否决——PLUS 高级混合在挂 RuntimeShader 的画笔上不生效，
按 SRC_OVER 输出导致黑框。光剖面（双峰光带 / 双侧边缘光）改用
「两道内缩 STROKE 环带 + SweepGradient 四停扫掠 + 沿光向 LinearGradient」等价实现，
形状仍是 Elbow geometry（x,y,w,h + geometry[8]=半径）。

**配置链路（v9.0 根治）**：真机日志实锤的两个时序坑——
①ColorOS OplusAppStartupManager 拦截 SystemUI 启动模块进程（Provider 失联）；
②onPackageReady 阶段 Application 可能未创建（SystemUI 侧读不到任何外部值）。
任何"等某进程/某 Context 可用"的方案都治标。v9.0 的根治：

**设置页 → LSPosed 服务 → RemotePreferences（LSPosed 自有存储）→ 注入第一帧即可读。**
- 设置页 onCreate 绑定 LSPosed 服务（XposedServiceHelper），绑定即把当前配置写入
  RemotePreferences；此后每次改动同步写；
- SystemUI 侧 `xi.getRemotePreferences("config")` 在注入第一帧就能读到同一份存储
  —— 不依赖 Application 创建时机、不依赖模块进程存活、ColorOS 拦不到 LSPosed 自己；
- 广播实时通道 + SystemUI 本地镜像 + Provider 兜底全部保留。
验证：设置页打开一次（服务绑定自动同步当前配置）→ 重启 → 开机初值即该配置。

## 三、客观数据（v4.9 → v5.0，同一测量脚本）

| 区域 | v4.9（有门控） | v5.0 | 变化 |
|---|---|---|---|
| 音量滑条边带 | 45.5 | **66.9** | **+21.4** |
| 亮度滑条边带 | 153.9 | 162.8 | +8.9（白色填充接近 255，加光空间小，物理上限） |
| 关态磁贴（v4.4 实测） | 基线 −5.8~+6.5 | +18~+33 | 平均 +26 |
| 背景（磁贴间空隙） | — | **Δ = 0.00** | 零外溢 |
| 媒体卡 圆角贴合（角/边） | 0.650 | 0.762 | 圆角改用 geometry[8] 后贴合更紧 |

**触摸穿透**：点自动旋转磁贴，状态成功切换（该区域 19260 px 变化）→ 加色层不挡触摸。
**性能**：`gfxinfo` p50=10ms / p90=20ms / p95=25ms、janky 11.85% —— 这是含开机与多次面板动画的混合样本，
**未做开关模块的干净 A/B**，不能归因，需要你判定流畅度。

## 四、实现要点（对应试错记录）

| 试错条目 | 本版做法 |
|---|---|
| A2 白圈是贴纸 | 不画环带，整块 PLUS 填充 |
| A3 形状不能猜 | 半径直接读 `geometry[8]`（实测：媒体卡 301×301 → 65.51；胶囊 → h/2） |
| B1/B2 Outline 拿不到 Path | 不依赖 Outline，用 Elbow 自己的几何 |
| R3 光随控件移动 | 每帧从 `nativeSubmitFrame` 拿几何，随动画同步 |
| R9 不得外溢 | 只画到该片 bbox；实测背景 Δ=0.00 |
| C2 不动 alpha | 不碰 `colors[]`，不动 Elbow 材质 |

插入位置（真机实测）：`NotificationShadeWindowView` 子节点中 Elbow 玻璃层在 index 6，
本层插 index 7 = **玻璃之上、控制中心内容之下**。

## 五、收起收缩环残留（v6.5 机制性根除，ROM 逆向实证）
**根因（翻 ROM SystemUI 包定位）**：ColorOS 的面板收起动画由
`com.oplus.systemui.shade.OplusShadeExpansionStateManagerExImpl.onPanelExpansionChanged`
驱动，fraction 语义 **1=完全展开 / 0=完全收起**（真机序列实测：
收起时 0.89 → 0.64 → … → 0.034 → 0）。旧版加色层不知道这个进度，
Elbow 玻璃先消失、我们的环带还在画 → 收缩环残留。

**修复**：挂 `onPanelExpansionChanged`，加色层所有 alpha ×= fraction；
fraction ≤ 0.01 时一帧都不画。真机验证：收起全程 fraction 连续采样（26 帧），
终点归零 → 光与面板严格同生同灭。前置的三重修复保留（空帧即清/面积骤减淡出/去节流）。

## 六、GPU/CPU 定制优化（v8.0 定稿，Immortalis-G720 TBDR 实测）
设备：PLF110 · 天玑9300（MT6989）· Immortalis-G720 · 1080×2392 OLED · 四档 120/90/60/30Hz。
G720 为 TBDR 架构（Arm 官方）：功耗大头 = 内存带宽 + 混合次数，不是片元计算。

**已上线（v8.0，零视觉变化，终端验证 220→208.9 / 0→36.8 可重复）**：
1. **全量帧指纹静止跳过**：FNV-1a 64 位哈希覆盖 count+42×N float+colors，
   逐位相同 → 零 GPU/CPU 工作。面板静止时 Elbow 仍每帧提交，此项直接砍掉全部无效渲染；
2. **单写者状态机**：面板可见性只在 UI 线程写（ROM 回调 post 到主线程），
   渲染线程只读不可变状态 —— 消灭 v7.x 的跨线程拉锯（有的有玻璃有的没玻璃的根因）；
3. **热路径零分配**：双缓冲+短锁，FNV 哈希 ~1000 次整型运算/帧，无可 GC 对象；
4. **单一 1s 看门狗**：配置同步+重挂检查合并。

**已验证不可做（v6.6/v6.7 翻车记录，防再犯）**：
- 弱签名去重（对称动画碰撞→白圈飘逸）；
- 填充内缩（PLUS 下第二硬边环）；
- AGSL（PLUS×shader 不生效→黑框）。

**后续可做（需先搭视觉回归 harness：录屏逐帧比对，未搭前不动手）**：
- RenderNode 离屏缓存（展开静止态录一次，动画时 replay）；
- 按 tile 分块 RenderNode（局部失效替代全 view 重绘）。
1. **静止去重**（核心）：几何签名不变 → 跳过重绘。面板静止时 Elbow 仍每帧提交相同数据，
   不去重会把 120Hz 钉死 → 现在静止时加色层零重绘，面板可自由降频；
2. **自适应分段**：光带 <16px 用 3 段 / <40px 用 4 段 / 其余 6 段（绘制调用最多省一半）；
3. **填充内缩**：中心填充只画到光带内侧，消除光带区双层 overdraw；
4. **单一 1s 看门狗**：合并配置轮询与重挂检查（减少常驻定时器唤醒）。
终验（交错三档）：lift 220→92.6 / 0→36.7 / 220→92.6，可重复，链路正常。

## 七、机型适配（PLF110 实测数据，非资料照抄）
OnePlus PLF110 · 联发科天玑 9300（MT6989）· Immortalis-G720 ·
1080×2392 OLED · 固定四档刷新率 30/60/90/120（非 LTPO 无级，ColorOS 帧率分类 normal=60/high=90）。
功耗要点：**静止时零重绘**（只有 Elbow 提交帧或配置变化才 invalidate）→ 静止时面板可自由降频；
绘制只覆盖磁贴 bbox（无全屏 overdraw）；热路径零分配。

## 七、v7.0/v7.1 修复
0. **熄屏/AOD 缩小环（v7.1）**：熄屏动画中 Elbow 仍提交收缩几何，且收缩磁贴会误触发
   "展开态自证"→ 熄屏画面出现大量缩小环。修复：submit 入口加
   `PowerManager.isInteractive()` 拦截 —— 非交互（熄屏/DOZE）状态下加色层零工作并清残留缓冲。
1. **锁屏拉控制中心闪烁**：锁屏场景 Elbow 会间歇提交空帧，旧版"立即清空→重画"交替即闪烁。
   修复：空帧 250ms 去抖——持续空才清，抖动保持画面；
2. **重启后配置丢失**：配置镜像（存于 SystemUI 自身 prefs，每次更新即写入）重启后回读。
   实证：重启前广播 lift=180 → 重启后磁贴环带 119（与重启前同档一致），镜像回读生效。
   注：此前镜像被我的测试广播覆盖过，属测试污染；现测试已结束，镜像只跟随你的设置。

## 八、已知限制

1. **接近白色的区域加不上光**（亮度滑条的白色填充、开态磁贴的 d7e9e9e9 底）——加色混合的物理上限，非 bug。
2. **squircle 未完全拟合**：2x2 磁贴若是平滑圆角（OplusOutline），`drawRoundRect` 与它有细微差别；半径已用真值，残差取决于圆角类型。
3. **帧同步**：几何来自 Elbow 渲染线程，视图重绘在 UI 帧 —— 静止态零差异，快速动画中理论上可能滞后一帧。
4. **面板重建**：若 Elbow/系统重建玻璃层，模块会在下一个 TextureView attach 时重新挂载（`onAttachedToWindow` 钩子常驻）。
5. 调试日志 `FULL42`（每次进程启动打 4 行，用于核对几何布局）保留，可在下版移除。

## 六、回滚

```bash
adb uninstall com.elbowmod.tileglow && adb reboot
# Elbow 配置本轮已逐字节还原（md5 b38f50d25bea77015e5fc6aff97e5be7），未留任何改动
```
