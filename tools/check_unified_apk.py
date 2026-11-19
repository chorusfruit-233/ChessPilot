#!/usr/bin/env python3
"""Validate the actual APK's modern module entry, class ownership and CPU dispatch.

Standard library only. --nnue additionally verifies that the expected network is
embedded exactly once, shared by both architecture variants.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def dex_classes(data):
    assert data.startswith(b"dex\n"), "Expected a standard DEX file"
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    string_count, string_offset = u32(56), u32(60)
    strings = []
    for index in range(string_count):
        offset = u32(string_offset + index * 4)
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + index * 4)] for index in range(u32(64))]
    classes = {}
    for index in range(u32(96)):
        offset = u32(100) + index * 32
        parent = u32(offset + 8)
        classes[types[u32(offset)]] = types[parent] if parent != 0xFFFFFFFF else None
    return types, classes


def check_arm64_alignment(data):
    assert data[:6] == b"\x7fELF\x02\x01", "Expected little-endian ELF64"
    assert struct.unpack_from("<H", data, 18)[0] == 183, "Expected an AArch64 library"
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phnum = struct.unpack_from("<HH", data, 54)
    for index in range(phnum):
        segment = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phsize)
        if segment[0] == 1:
            assert segment[7] >= 16384, "ELF LOAD segments must support 16 KB pages"


def check_elf(data):
    check_arm64_alignment(data)
    shoff = struct.unpack_from("<Q", data, 40)[0]
    shsize, shnum, names_index = struct.unpack_from("<HHH", data, 58)
    headers = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + index * shsize)
               for index in range(shnum)]
    names_header = headers[names_index]
    names = data[names_header[4]:names_header[4] + names_header[5]]
    sections = {names[h[0]:names.index(b"\0", h[0])].decode(): h for h in headers}
    for name in ("armv8_init", "armv8_dotprod_init"):
        assert name in sections and sections[name][5] > 0, f"Missing inert {name} initializers"
    dynamic = sections[".dynamic"]
    entries = dict(struct.iter_unpack("<QQ", data[dynamic[4]:dynamic[4] + dynamic[5]]))
    init = sections[".init_array"]
    assert entries[25] == init[3] and entries[27] == init[5], "Loader must run only baseline-safe startup"
    for name in ("armv8_init", "armv8_dotprod_init"):
        variant = sections[name]
        assert variant[3] >= init[3] + init[5] or variant[3] + variant[5] <= init[3]
    assert b"info string ChessPilot CPU " in data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--nnue", type=Path)
    args = parser.parse_args()
    entry = "io.github.a1175815821.chesspilot.MainHook"
    prefix = "META-INF/xposed/"
    with zipfile.ZipFile(args.apk) as apk:
        def lines(name):
            return [line.strip() for line in apk.read(prefix + name).decode().splitlines()
                    if line.strip() and not line.lstrip().startswith("#")]
        assert lines("java_init.list") == [entry], "Hot reload requires exactly one module entry"
        assert lines("scope.list") == ["com.duolingo"]
        props = dict(line.split("=", 1) for line in lines("module.prop"))
        assert props["minApiVersion"] == props["targetApiVersion"] == "102"
        assert props["autoHotReload"] == props["staticScope"] == "true"
        assert "assets/xposed_init" not in apk.namelist(), "Legacy entry must be removed"
        types, classes = set(), {}
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                dex_types, dex_definitions = dex_classes(apk.read(name))
                types.update(dex_types)
                classes.update(dex_definitions)
        assert classes["L" + entry.replace(".", "/") + ";"] == "Lio/github/libxposed/api/XposedModule;"
        for required in ["Lcom/chessassist/ChessPilotApp;", "Lcom/chessassist/MainActivity;",
                         "Lio/github/libxposed/service/XposedProvider;",
                         "Lorg/luckypray/dexkit/DexKitBridge;"]:
            assert required in classes, f"Missing unified app component {required}"
        assert not any(name.startswith("Lio/github/libxposed/api/") for name in classes), "Framework API must remain compileOnly"
        assert not any(name.startswith("Lde/robv/android/xposed/") for name in types), "Legacy Xposed references remain"
        libraries = {name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
        assert libraries == {"lib/arm64-v8a/libstockfish.so", "lib/arm64-v8a/libdexkit.so"}, libraries
        check_arm64_alignment(apk.read("lib/arm64-v8a/libdexkit.so"))
        engine = apk.read("lib/arm64-v8a/libstockfish.so")
    check_elf(engine)
    if args.nnue:
        net = args.nnue.read_bytes()
        digest = hashlib.sha256(net).hexdigest()
        assert len(net) == 98511183 and digest.startswith("1a298aa575a0"), "Unexpected SF19 network"
        assert engine.count(net) == 1, "Network must be embedded exactly once"
    print(json.dumps({"apk": str(args.apk), "api": 102, "entry_count": 1,
                      "defined_classes": len(classes), "cpu_paths": ["armv8", "armv8-dotprod"],
                      "embedded_nnue_copies": 1 if args.nnue else "not checked"}, indent=2))


if __name__ == "__main__":
    main()
