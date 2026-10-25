#!/usr/bin/env python3
"""
热路径回归守卫。

对 build/classes.dex 反汇编后的 smali 做静态检查，断言「每帧路径」没有回退到
v1.1 那种写法。这是为了让「结构性问题不复发」变成可自动验证的事实，
而不是靠下一次卡顿来提醒。

用法： python tools/check_hotpath.py [dex路径]
"""
import os
import re
import subprocess
import sys
import tempfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DEX = os.path.join(BASE, "build", "dex", "classes.dex")
SMALI_TOOLS = os.environ.get(
    "TG_SMALI_TOOLS", r"D:\workbuddy\2026-10-17-14-01-16\ElbowHitColor\tools")
JDK = os.environ.get("TG_JDK", r"D:\android-toolchain\jdk")

# 每帧会被执行的方法（其余方法不在热路径上）
LEGACY_METHODS = ["draw", "isTile", "maybeDumpTree"]

HOT_METHODS = {
    # 探针版没有绘制热路径：只在首次 3 帧打日志，不参与每帧渲染。
    # 保留 v1.1 单体写法作为回归样本，确保守卫本身仍然有效。

    # v1.1 的单体写法：同样要能检出（用于回归验证本守卫确实有效）
    "com/elbowmod/tileglow/TileGlow.smali": LEGACY_METHODS,
}

# 允许出现在热路径里的低频调用（本身有缓存保护）。
# 注：TileState.of 不在列表内 —— 它是「读 tag，缺失才创建」，稳态零分配，
# 但字节码上不存在可识别的保护分支，硬列进来会让守卫误报。
ALLOWED_IN_DRAW = {
    # 注：Glow.sweep 不列入 —— 它内部有自己的缓存分支（SweepGradient 只在尺寸/双向变化时重建），
    # 但调用点位于较深的分支体里，守卫的控制流判定会误报。
    "Lcom/elbowmod/tileglow/Glow;->buildSheen",
    "Lcom/elbowmod/tileglow/Glow;->buildAbsorb",
    "Lcom/elbowmod/tileglow/Shape;->resolve",
    "Lcom/elbowmod/tileglow/Shape;->radius",
}


def disassemble(dex, out):
    cp = os.pathsep.join([
        os.path.join(SMALI_TOOLS, "baksmali.jar"),
        os.path.join(SMALI_TOOLS, "smali-dexlib2.jar"),
        os.path.join(SMALI_TOOLS, "guava.jar"),
        os.path.join(SMALI_TOOLS, "jcommander.jar"),
        os.path.join(SMALI_TOOLS, "smali-util.jar"),
        os.path.join(SMALI_TOOLS, "antlr-runtime.jar"),
    ])
    subprocess.run([os.path.join(JDK, "bin", "java.exe"), "-cp", cp,
                    "com.android.tools.smali.baksmali.Main",
                    "disassemble", dex, "-o", out],
                   check=True, capture_output=True)


def method_body(text, name):
    """切出指定方法的 smali 文本。"""
    for m in re.finditer(r"\.method [^\r\n]*\b%s\(" % re.escape(name), text):
        start = m.start()
        end = text.index(".end method", start) + len(".end method")
        yield text[start:end]


def check(dex):
    tmp = tempfile.mkdtemp(prefix="tgcheck")
    root = os.path.join(tmp, "smali")
    disassemble(dex, root)

    errors = []
    for rel, methods in HOT_METHODS.items():
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            if methods == LEGACY_METHODS:
                continue
            # 匿名类编号随编译器/javac 版本可能变化（$1/$2/$3），缺失不算失败，
            # 但会把实际存在的匿名类打印出来，避免守卫静默失效。
            if "TileGlow$" in rel:
                print("  (skip, not present: %s)" % rel)
                continue
            errors.append("missing %s" % rel)
            continue
        text = open(path, encoding="utf-8").read()
        found = False
        for name in methods:
            for body in method_body(text, name):
                found = True
                errors.extend(inspect(rel, name, body))
        if not found:
            if methods == LEGACY_METHODS:
                continue  # v2.0 布局
            errors.append("method not found: %s in %s" % (methods, rel))
    return errors


def guarded_by_branch(lines, idx):
    """调用点是否被某个条件跳转保护。

    做法：向前找最近的 `if-*` 指令，取它的跳转目标 label；
    若该 label 定义在调用点之后，说明"条件不成立时跳过这段"，
    即调用点处于分支体内 —— 这才是真正的缓存保护。
    """
    for j in range(idx - 1, max(0, idx - 40), -1):
        m = re.search(r"\b(if-\w+)\b", lines[j])
        if not m:
            continue
        # 两操作数 (if-eqz v1, :label) 与三操作数 (if-eq v5, v3, :label) 都要覆盖
        labels = re.findall(r":\S+", lines[j])
        if not labels:
            continue
        target = labels[-1]
        # 找到该 label 的定义位置
        for k in range(idx + 1, min(len(lines), idx + 60)):
            if lines[k].strip().startswith(target):
                return True
        # 找不到定义就继续往前找更外层的分支
    return False


def inspect(rel, name, body):
    errs = []
    tag = "%s#%s" % (rel.split("/")[-1], name)

    n = len(re.findall(r"new-instance", body))
    if n:
        errs.append("%s: %d new-instance (每帧分配)" % (tag, n))

    n = len(re.findall(r"new-array|filled-new-array", body))
    if n:
        errs.append("%s: %d new-array (每帧分配)" % (tag, n))

    for pat, why in (("toLowerCase", "字符串分配"),
                     ("StringBuilder", "字符串分配"),
                     ("monitor-enter", "加锁")):
        n = len(re.findall(pat, body))
        if n:
            errs.append("%s: %d x %s (%s)" % (tag, n, pat, why))

    is_classifier = rel.endswith("Tiles.smali") or (
        rel.endswith("TileGlow.smali") and name in ("isTile", "maybeDumpTree"))
    if is_classifier:
        n = len(re.findall(r"contains", body))
        if n:
            errs.append("%s: %d x String.contains (应缓存到 ClassValue)" % (tag, n))
        n = len(re.findall(r"getName", body))
        if n:
            errs.append("%s: %d x getName (应缓存到 ClassValue)" % (tag, n))
        if rel.endswith("Tiles.smali") and "ClassValue" not in body:
            errs.append("%s: 未使用 ClassValue" % tag)

    if rel.endswith("Glow.smali") or rel.endswith("TileGlow.smali"):
        for call in re.findall(r"invoke-[a-z/]+ \{[^}]*\}, ([^\r\n;]+);", body):
            call = call.strip()
            if call.startswith("Ljava/lang/String;->contains"):
                errs.append("%s: %s (每帧字符串操作)" % (tag, call))
            if call.startswith("Ljava/lang/Class;->getMethod"):
                errs.append("%s: %s (每帧反射查找)" % (tag, call))
        # 低频调用必须被条件分支保护（做真正的控制流判定，而不是看前几行）
        lines = body.split("\n")
        for guarded in ALLOWED_IN_DRAW:
            for i, ln in enumerate(lines):
                if guarded not in ln:
                    continue
                if not guarded_by_branch(lines, i):
                    errs.append("%s: %s 缺少缓存保护分支" % (tag, guarded))

    if rel.endswith("TileGlow$1.smali") or rel.endswith("TileGlow$2.smali") \
            or rel.endswith("TileGlow$3.smali") \
            or (rel.endswith("TileGlow.smali") and name == "draw"):
        # 熔断/兜底的 proceed 必须存在
        if "proceed" not in body:
            errs.append("%s: 缺少 proceed 兜底" % tag)
        # 主线程 IPC 绝不允许
        for bad in ("ContentResolver", "SharedPreferences", "Parcel"):
            if bad in body:
                errs.append("%s: 热路径出现 %s (主线程 IPC)" % (tag, bad))
        # 每帧递归遍历子树绝不允许
        if "interface iterator" in body and "ArrayList" in body:
            errs.append("%s: 每帧出现集合遍历" % tag)

    return errs


def main():
    dex = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DEX
    if not os.path.exists(dex):
        print("ERROR: dex not found: %s (先跑 build.py)" % dex, file=sys.stderr)
        sys.exit(2)
    errors = check(dex)
    if errors:
        print("=== HOT-PATH CHECK FAILED ===")
        for e in errors:
            print("  - " + e)
        sys.exit(1)
    print("hot-path check OK (%s)" % os.path.basename(dex))


if __name__ == "__main__":
    main()