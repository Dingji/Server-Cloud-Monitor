"""离线探针：在 iOS platform klib 里查某个 ObjC 选择器/名字是否被生成为 Kotlin 声明。

用法: python tools/probe_klib.py <klib名,如 platform.Foundation> <关键字> [关键字...]
klib 可能是目录，也可能是 zip；两种都支持。
"""
import os
import sys
import zipfile

KONAN = os.path.expanduser(
    "~/.konan/kotlin-native-prebuilt-windows-x86_64-2.4.21/klib/platform/ios_arm64"
)


def blobs(path):
    if os.path.isdir(path):
        for root, _, files in os.walk(path):
            for f in files:
                p = os.path.join(root, f)
                try:
                    with open(p, "rb") as fh:
                        yield p, fh.read()
                except OSError:
                    pass
    else:
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                yield n, z.read(n)


def main():
    name = sys.argv[1]
    keys = sys.argv[2:]
    path = os.path.join(KONAN, "org.jetbrains.kotlin.native.platform." + name) if not name.startswith("org.") else os.path.join(KONAN, name)
    if not os.path.exists(path):
        path = path + ".klib"
    print("probe:", path, os.path.isdir(path) and "[dir]" or "[zip]")
    found = {k: 0 for k in keys}
    total = 0
    for member, data in blobs(path):
        if "linkdata" not in member:
            continue
        total += 1
        for k in keys:
            if k.encode() in data:
                found[k] += 1
    print("linkdata members:", total)
    for k in keys:
        print("  %-24s -> %s" % (k, "HIT x%d" % found[k] if found[k] else "absent"))


if __name__ == "__main__":
    main()
