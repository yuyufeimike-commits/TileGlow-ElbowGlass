#!/usr/bin/env python3
"""
独立模块一键构建：javac -> d8 -> aapt2 link -> 注入 dex/META-INF -> zipalign -> 签名 -> 校验

结构性问题修复：
  1. 不再硬编码依赖 ElbowHitColor/ 目录（原先依赖其 build/libxposed-classes 与
     tools/mod-debug.keystore，删掉那个目录模块就无法构建）。
     现在优先用本目录 tools/ 下的 libxposed-api.jar 与 keystore；
     找不到时才回退到 D:/android-toolchain 的公共工具链。
  2. 版本号从 AndroidManifest.xml 读取，输出名 = TileGlow-v<versionName>.apk，
     不再出现「文件名 v1.1 / versionCode=1」这种不一致。
  3. 构建后自动做结构自检（见 verify_apk），失败即非零退出。
"""
import os
import re
import shutil
import subprocess
import sys
import zipfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(BASE, "tools")
BUILD = os.path.join(BASE, "build")
RES = os.path.join(BASE, "res")
META = os.path.join(BASE, "meta-inf", "xposed")
MANIFEST = os.path.join(BASE, "AndroidManifest.xml")

# ---- 工具链（可被环境变量覆盖）----
JDK = os.environ.get("TG_JDK", r"D:\android-toolchain\jdk")
BT = os.environ.get("TG_BT", r"D:\android-toolchain\sdk\build-tools\35.0.0")
SDK = os.environ.get("TG_SDK", r"D:\android-toolchain\sdk")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")

# ---- 编译期依赖（libxposed API，已随模块自带）----
LIBXPOSED = os.path.join(TOOLS, "libxposed-api.jar")
SVC = os.path.join(TOOLS, "libxposed-service.jar")

# ---- 签名密钥（已随模块自带）----
KS = os.path.join(TOOLS, "mod-debug.keystore")
KS_PASS = "android"
KS_ALIAS = "modkey"


def run(cmd, env=None, quiet=False):
    # Windows 控制台默认 GBK，工具输出可能含 UTF-8；显式用 errors='replace' 避免解码崩溃
    r = subprocess.run(cmd, capture_output=True, text=True, env=env,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print("FAILED:", " ".join(str(c) for c in cmd[:4]), file=sys.stderr)
        print((r.stdout or "")[-4000:], file=sys.stderr)
        print((r.stderr or "")[-4000:], file=sys.stderr)
        sys.exit(1)
    if not quiet and r.stdout:
        print(r.stdout.rstrip())
    return r


def read_version():
    txt = open(MANIFEST, encoding="utf-8").read()
    vc = re.search(r'android:versionCode\s*=\s*"(\d+)"', txt)
    vn = re.search(r'android:versionName\s*=\s*"([^"]+)"', txt)
    return (int(vc.group(1)) if vc else 1, vn.group(1) if vn else "1.0")


def pick(path, what):
    if os.path.exists(path):
        return path
    print("ERROR: missing %s (%s)" % (path, what), file=sys.stderr)
    sys.exit(1)


def main():
    version_code, version_name = read_version()
    out = os.path.join(BUILD, "TileGlow-v%s.apk" % version_name)
    libxposed = pick(LIBXPOSED, "libxposed-api.jar (run tools/sync_libxposed.py)")
    keystore = pick(KS, "mod-debug.keystore")

    for d in ("classes", "dex"):
        shutil.rmtree(os.path.join(BUILD, d), ignore_errors=True)
        os.makedirs(os.path.join(BUILD, d), exist_ok=True)

    # ---------- 1) javac ----------
    srcs = []
    for root, _, files in os.walk(os.path.join(BASE, "src")):
        for f in files:
            if f.endswith(".java"):
                srcs.append(os.path.join(root, f))
    if not srcs:
        print("ERROR: no java sources", file=sys.stderr)
        sys.exit(1)
    print("javac: %d files" % len(srcs))
    run([os.path.join(JDK, "bin", "javac.exe"), "-nowarn", "-encoding", "UTF-8",
         "-source", "8", "-target", "8",
         "-bootclasspath", ANDROID_JAR, "-cp", libxposed + os.pathsep + SVC,
         "-d", os.path.join(BUILD, "classes")] + srcs)

    # ---------- 1.5) service 接口类随模块打包（设置页 App 进程需要） ----------
    svcjar = os.path.join(TOOLS, "libxposed-service.jar")
    if os.path.exists(svcjar):
        import zipfile as _zf
        with _zf.ZipFile(svcjar) as zz:
            for n in zz.namelist():
                if n.endswith(".class"):
                    dst = os.path.join(BUILD, "classes", n)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    with open(dst, "wb") as f:
                        f.write(zz.read(n))

    # ---------- 2) d8 ----------
    classes = []
    for root, _, files in os.walk(os.path.join(BUILD, "classes")):
        for f in files:
            if f.endswith(".class"):
                classes.append(os.path.join(root, f))
    run([os.path.join(JDK, "bin", "java.exe"), "-cp", os.path.join(BT, "lib", "d8.jar"),
         "com.android.tools.r8.D8", "--release", "--min-api", "26",
         "--lib", ANDROID_JAR, "--classpath", libxposed,
         "--output", os.path.join(BUILD, "dex")] + classes)

    # ---------- 3) aapt2 compile + link ----------
    # 注：本机 aapt2 link 直接吃 res 目录会报「数据无效(13)」，
    #     必须先 compile 成 zip 再 link。固定走两步，避免环境差异。
    res_zip = os.path.join(BUILD, "res.zip")
    if os.path.exists(res_zip):
        os.remove(res_zip)
    run([os.path.join(BT, "aapt2.exe"), "compile", "--dir", RES, "-o", res_zip])

    base_apk = os.path.join(BUILD, "base.apk")
    if os.path.exists(base_apk):
        os.remove(base_apk)
    run([os.path.join(BT, "aapt2.exe"), "link", "-o", base_apk,
         "-I", ANDROID_JAR, "--manifest", MANIFEST, res_zip,
         "--min-sdk-version", "26", "--target-sdk-version", "35"])

    # ---------- 4) 打包 ----------
    unsigned = os.path.join(BUILD, "unsigned.apk")
    zin = zipfile.ZipFile(base_apk)
    zout = zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED)
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    zin.close()
    zout.write(os.path.join(BUILD, "dex", "classes.dex"), "classes.dex")
    for name in ("java_init.list", "module.prop", "scope.list"):
        zout.write(os.path.join(META, name), "META-INF/xposed/" + name)
    zout.close()

    # ---------- 5) zipalign + 签名 ----------
    aligned = os.path.join(BUILD, "aligned.apk")
    run([os.path.join(BT, "zipalign.exe"), "-f", "-p", "4", unsigned, aligned])

    env = dict(os.environ, JAVA_HOME=JDK)
    run([os.path.join(BT, "apksigner.bat"), "sign",
         "--ks", keystore, "--ks-pass", "pass:" + KS_PASS,
         "--key-pass", "pass:" + KS_PASS, "--ks-key-alias", KS_ALIAS,
         "--out", out, aligned], env=env)

    run([os.path.join(BT, "apksigner.bat"), "verify", out], env=env, quiet=True)

    # ---------- 6) 热路径守卫 ----------
    guard = os.path.join(TOOLS, "check_hotpath.py")
    if os.path.exists(guard):
        run([sys.executable, guard], quiet=True)

    # ---------- 7) 结构自检 ----------
    verify_apk(out, version_code, version_name, srcs)

    print("built: %s (%d bytes)" % (out, os.path.getsize(out)))
    print("install: adb install -r %s   (仅安装，不做任何设备写操作)" % out)


def verify_apk(apk, version_code, version_name, srcs):
    """构建后自检：拦住「产物和源码不一致」这类结构性回归。"""
    errs = []
    z = zipfile.ZipFile(apk)
    names = set(z.namelist())

    for must in ("classes.dex", "AndroidManifest.xml", "META-INF/xposed/java_init.list",
                 "META-INF/xposed/module.prop", "META-INF/xposed/scope.list"):
        if must not in names:
            errs.append("missing " + must)

    dex = z.read("classes.dex")
    blob = dex.decode("latin-1", "ignore")

    # 入口类必须存在
    for cls in ("Module", "TileGlow", "Config", "ElbowGlow",
                "GlowLayerView", "Prefs", "ConfigProvider", "SettingsActivity"):
        if "Lcom/elbowmod/tileglow/%s;" % cls not in blob:
            errs.append("dex missing class %s" % cls)

    # 死代码必须已经清除
    for gone in ("Renderer", "Glass", "Glow", "Shape", "Tiles", "TileState"):
        if ("Lcom/elbowmod/tileglow/%s;" % gone) in blob:
            errs.append("self-drawn class %s still present (should be Elbow-only)" % gone)

    for dead in ("hookElbowDim", "elbow dim", "COMPEN", "compensate",
                 "maybeDumpTree", "TILE MATCH", "view class seen"):
        if dead in blob:
            errs.append("dead code still present: %s" % dead)

    # ---- 危险 API：源码级精确检查（剥离注释后再匹配，避免文档里的反例被误报）----
    import re as _re

    def strip_comments(text):
        text = _re.sub(r"/\*.*?\*/", " ", text, flags=_re.S)
        text = _re.sub(r"//[^\n]*", " ", text)
        return text

    HOT = ("Tiles.java", "Glow.java", "Shape.java", "TileState.java")
    for path in srcs:
        raw = open(path, encoding="utf-8").read()
        code = strip_comments(raw)
        name = os.path.basename(path)
        if _re.search(r"\.setBackground\(\s*null\s*\)", code):
            errs.append("setBackground(null) in %s (irreversible)" % name)
        if "WeakHashMap" in code and name in HOT:
            errs.append("WeakHashMap in hot path file %s" % name)
        # 热路径文件不得直接 new Paint/Path/RectF/Gradient
        # Glow 是每帧渲染器：不得出现任何局部绘制对象（全部缓存在 TileState）。
        # Shape 里的 new Path/new RectF 只在静态槽位与诊断里，不属于每帧路径。
        if name == "Glow.java":
            for obj in ("new Paint(", "new Path(", "new RectF("):
                if obj in code:
                    errs.append("%s in Glow (绘制对象必须缓存在 TileState)" % obj)

    # v3 能力断言：形状捕获 + 双向光；材质类 API 必须完全消失
    for must in ("nativeSubmitFrame", "onAttachedToWindow", "com.treatangus.elbowhitcolor"):
        if must not in blob:
            errs.append("v3 capability missing: %s" % must)
    for forbid in ("setRenderEffect", "OplusQsDeformRadiusParam", "setPathProvider"):
        if forbid in blob:
            errs.append("material-touching API present: %s (会与 Elbow 冲突)" % forbid)

    # 热路径不得出现同步 Binder：ContentResolver 只允许出现在 Config 的后台线程里
    if "ContentResolver" in blob and "TileGlowCfg" not in blob:
        errs.append("ContentResolver fallback present but no background thread")

    prop = z.read("META-INF/xposed/module.prop").decode("utf-8", "ignore")
    if "versionCode=%d" % version_code not in prop:
        errs.append("module.prop versionCode mismatch")
    if ("version=" + version_name) not in prop:
        errs.append("module.prop version mismatch")

    init = z.read("META-INF/xposed/java_init.list").decode("utf-8", "ignore").strip()
    if init != "com.elbowmod.tileglow.Module":
        errs.append("java_init.list wrong: %s" % init)

    scope = z.read("META-INF/xposed/scope.list").decode("utf-8", "ignore").strip()
    if scope != "com.android.systemui":
        errs.append("scope.list wrong: %s" % scope)

    z.close()

    # 版本号一致性（badging）
    env = dict(os.environ, JAVA_HOME=JDK)
    r = subprocess.run([os.path.join(BT, "aapt2.exe"), "dump", "badging", apk],
                       capture_output=True, text=True, env=env)
    head = (r.stdout or "").splitlines()[0] if r.stdout else ""
    if "versionCode='%d'" % version_code not in head:
        errs.append("manifest versionCode mismatch: %s" % head)

    if errs:
        print("\n=== SELF-CHECK FAILED ===", file=sys.stderr)
        for e in errs:
            print("  - " + e, file=sys.stderr)
        sys.exit(2)
    print("self-check OK")


if __name__ == "__main__":
    main()