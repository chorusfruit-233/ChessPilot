#!/usr/bin/env python3
"""
Re-derive R8-obfuscated chess class names for a new Duolingo APK.

Repeats the manual procedure documented in docs/REVERSE_ENGINEERING.md:
  1. mine dex files for Kotlin data-class toString() markers
     ("ChessGameState(fen=", "ChessFen(piecePlacementString=", ...)
  2. resolve which minified class owns each marker
  3. locate the Rive board controller: the class with a 0-arg getter returning
     ChessGameState AND a 6-arg (Square, Square, boolean, ArrowType, int, ArrowColor)
     arrow method
  4. write module/src/main/assets/remap.json

Usage:
  python tools/remap.py --apk 多邻国_6.105.2.apks  # .apks/.apkm bundle or plain .apk
  python tools/remap.py --apk 多邻国_6.105.2.apks --check
  pip install androguard==3.3.5                    # required
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import zipfile

try:
    from androguard.core.bytecodes.dvm import DalvikVMFormat
except ImportError:
    sys.exit("pip install androguard==3.3.5")

MARKERS = {
    "chessGameState": "ChessGameState(fen=",
    "chessFen": "ChessFen(piecePlacementString=",
    "board": "ChessBoardState(board=",
    "square": "ChessCoordinate(row=",
    "move": "ChessMove(from=",
    "setupModel": "ChessGameSetupModel(fenNotation=",
    "point": "RiveCoordinate(x=",
    "arrowCoordinates": "RiveArrowCoordinates(start=",
    "arrowModel": "ShowArrow(arrowType=",
    "boardUpdateEvent": "HideValidMoves(gameState=",
    "resyncState": "ResyncGameState(fen=",
    "resetArrows": "ResetArrows",
}

# Kept (unobfuscated) names — stable across versions.
KEPT = {
    "arrowType": "com.duolingo.chess.model.rive.ArrowType",
    "arrowColor": "com.duolingo.chess.model.rive.ArrowColor",
    "squareHighlightType": "com.duolingo.chess.model.rive.SquareHighlightType",
    "pieceType": "com.duolingo.chess.model.ChessPieceType",
    "playerColor": "com.duolingo.chess.model.ChessPlayerColor",
}


def extract_base_apk(path):
    """Return bytes of the base APK from an .apks/.apkm bundle, or the file itself."""
    path = str(path)
    if path.lower().endswith((".apks", ".apkm")):
        with zipfile.ZipFile(path) as z:
            apks = [n for n in z.namelist() if n.lower().endswith(".apk")]
            bases = [n for n in apks if Path(n).name.lower() in ("base.apk", "base-master.apk")]
            if len(bases) == 1:
                return z.read(bases[0])
            if len(apks) == 1:
                return z.read(apks[0])
        raise ValueError("cannot uniquely identify base APK in bundle")
    with open(path, "rb") as f:
        return f.read()


def load_dexes(base_apk_bytes):
    dexes = {}
    with zipfile.ZipFile(io.BytesIO(base_apk_bytes)) as z:
        for n in z.namelist():
            if re.match(r"classes\d*\.dex$", n):
                dexes[n] = DalvikVMFormat(z.read(n))
    return dexes


def find_marker_owners(dexes):
    """Marker string → defining class (via toString() bodies)."""
    owners = {}
    for d in dexes.values():
        for m in d.get_methods():
            code = m.get_code()
            if not code or m.get_name() != "toString":
                continue
            try:
                for inst in code.get_bc().get_instructions():
                    if "const-string" not in inst.get_name():
                        continue
                    s = inst.get_string()
                    for key, marker in MARKERS.items():
                        if marker in s:
                            owner = m.get_class_name()
                            if key in owners and owners[key] != owner:
                                raise ValueError(f"ambiguous {key}: {owners[key]} and {owner}")
                            owners[key] = owner
            except ValueError:
                raise
            except Exception:
                continue
    return owners


def short(cls):
    if cls and cls.startswith("L") and cls.endswith(";"):
        return cls[1:-1].replace("/", ".")
    return cls


def parse_params(descriptor):
    """Split a JVM method descriptor into param type strings.

    e.g. "(Lge4;Lge4;ZLpkg/ArrowType;ILpkg/ArrowColor;)V"
      -> ["Lge4;", "Lge4;", "Z", "Lpkg/ArrowType;", "I", "Lpkg/ArrowColor;"]
    """
    # Androguard 3.x inserts spaces between parameters, unlike JVM descriptors.
    # Treating those spaces as types made every multi-arg signature miss.
    inner = descriptor.replace(" ", "").split("(", 1)[1].rsplit(")", 1)[0]
    params = []
    i = 0
    while i < len(inner):
        c = inner[i]
        if c == "L":
            j = inner.index(";", i)
            params.append(inner[i:j + 1])
            i = j + 1
        elif c == "[":
            # array: consume brackets then the element type
            j = i
            while inner[j] == "[":
                j += 1
            if inner[j] == "L":
                k = inner.index(";", j)
                params.append(inner[i:k + 1])
                i = k + 1
            else:
                params.append(inner[i:j + 1])
                i = j + 1
        else:
            params.append(c)
            i += 1
    return params


def find_controller(dexes, state_short):
    """
    Board controller has a 0-arg state getter and an exact arrow signature:
      (Square, Square, boolean, ArrowType, int, ArrowColor) -> void.
    Returns (controller_class, square_class): the first two params of the
    arrow method ARE the Square type (mirrors ArrowPainter.resolveDrawMethod).
    """
    state_desc = "L%s;" % state_short.replace(".", "/")
    candidates = []
    for d in dexes.values():
        for c in d.get_classes():
            cname = c.get_name()
            methods = c.get_methods()
            has_state_getter = any(
                m.get_code() is not None and
                m.get_descriptor().replace(" ", "") == "()" + state_desc
                for m in methods
            )
            if not has_state_getter:
                continue
            arrow_methods = []
            for m in methods:
                params = parse_params(m.get_descriptor())
                if (m.get_code() is not None and len(params) == 6 and
                        params[0].startswith("L") and params[0] == params[1] and
                        params[2:] == ["Z", "L" + KEPT["arrowType"].replace(".", "/") + ";",
                                       "I", "L" + KEPT["arrowColor"].replace(".", "/") + ";"] and
                        m.get_descriptor().endswith(")V")):
                    arrow_methods.append(m)
            if arrow_methods:
                candidates.append((cname, arrow_methods[0]))
    if not candidates:
        return None, None
    if len(candidates) != 1:
        raise ValueError("ambiguous board controllers: " + ", ".join(c[0] for c in candidates))
    ctrl, arrow = candidates[0]
    square = parse_params(arrow.get_descriptor())[0]
    return ctrl, square


def find_class(dexes, descriptor):
    for dex in dexes.values():
        for cls in dex.get_classes():
            if cls.get_name() == descriptor:
                return cls
    raise ValueError(f"class definition not found: {descriptor}")


def find_clear_method(controller, reset_descriptor):
    """Find the zero-arg method dispatching the ResetArrows singleton event."""
    candidates = []
    for method in controller.get_methods():
        if method.get_descriptor() != "()V" or not method.get_code():
            continue
        if any(inst.get_name().startswith("sget-object") and
               reset_descriptor + "->" in inst.get_output()
               for inst in method.get_code().get_bc().get_instructions()):
            candidates.append(method.get_name())
    if len(candidates) != 1:
        raise ValueError(f"cannot uniquely identify clearArrows method: {candidates}")
    return candidates[0]


def verify_runtime_layout(dexes, owners):
    """Check the fields/getters still read by StateBridge and ArrowPainter."""
    color = "L" + KEPT["playerColor"].replace(".", "/") + ";"
    contracts = {
        "chessGameState": ({"a": owners["chessFen"], "d": color, "e": color,
                            "f": "Ljava/util/List;"}, []),
        "chessFen": ({"a": "Ljava/lang/String;", "b": color,
                      **{key: "Z" for key in "cdef"}, "g": "Ljava/lang/String;",
                      "h": "I", "i": "I"}, [("b", "()Ljava/lang/String;")]),
        "move": ({}, [("b", "()Ljava/lang/String;")]),
        "square": ({"a": "I", "b": "I"}, [("<init>", "(II)V")]),
    }
    for key, (fields, methods) in contracts.items():
        cls = find_class(dexes, owners[key])
        actual_fields = {f.get_name(): f.get_descriptor() for f in cls.get_fields()}
        actual_methods = {(m.get_name(), m.get_descriptor().replace(" ", ""))
                          for m in cls.get_methods()}
        if any(actual_fields.get(name) != kind for name, kind in fields.items()) or \
                any(method not in actual_methods for method in methods):
            raise ValueError(f"{key} layout changed; update the runtime accessors before remapping")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="module/src/main/assets/remap.json")
    ap.add_argument("--check", action="store_true", help="verify the existing table without writing")
    args = ap.parse_args()

    base = extract_base_apk(args.apk)
    print("parsing dex files (this can take a minute)…")
    dexes = load_dexes(base)

    owners = find_marker_owners(dexes)
    missing = set(MARKERS) - owners.keys()
    if missing:
        sys.exit("missing model markers (table left unchanged): " + ", ".join(sorted(missing)))
    verify_runtime_layout(dexes, owners)

    # raw=True is required for bytes; APK(BytesIO(...)) is interpreted as a path.
    from androguard.core.bytecodes.apk import APK
    apk = APK(base, raw=True)
    if apk.get_package() != "com.duolingo" or not apk.get_androidversion_name():
        sys.exit("expected a versioned com.duolingo base APK (table left unchanged)")
    remap = {
        "comment": "Generated by tools/remap.py from the exact APK; regenerate and rebuild the module after a Duolingo update.",
        "appVersion": apk.get_androidversion_name(),
        "appVersionCode": int(apk.get_androidversion_code()),
        "sourceSha256": hashlib.sha256(Path(args.apk).read_bytes()).hexdigest(),
        "classes": {key: [short(owner)] for key, owner in owners.items() if key != "resetArrows"},
    }

    for key, kept in KEPT.items():
        remap["classes"][key] = [kept]

    ctrl, square = find_controller(dexes, short(owners["chessGameState"]))
    if not ctrl or square != owners["square"]:
        sys.exit("controller/square signature mismatch (table left unchanged)")
    remap["classes"]["boardController"] = [short(ctrl)]
    controller = find_class(dexes, ctrl)
    remap["methods"] = {"clearArrows": [find_clear_method(controller, owners["resetArrows"])]}

    # ShowArrow's third constructor parameter is the piece type, even when its
    # toString has been optimized away. Do not carry stale names into a new APK:
    # short R8 names can resolve to completely unrelated classes after an update.
    arrow = find_class(dexes, owners["arrowModel"])
    constructors = [m for m in arrow.get_methods() if m.get_name() == "<init>" and
                    len(parse_params(m.get_descriptor())) == 5]
    if len(constructors) != 1:
        sys.exit("ShowArrow constructor mismatch (table left unchanged)")
    remap["classes"]["piece"] = [short(parse_params(constructors[0].get_descriptor())[2])]
    for names in remap["classes"].values():
        for name in names:
            find_class(dexes, "L" + name.replace(".", "/") + ";")
    for key, names in remap["classes"].items():
        print(f"  {key:18s} -> {', '.join(names)}")
    print(f"  clearArrows        -> {remap['methods']['clearArrows'][0]}")
    print(f"  appVersion         -> {remap['appVersion']} ({remap['appVersionCode']})")

    if args.check:
        if json.loads(Path(args.out).read_text(encoding="utf-8")) != remap:
            sys.exit(f"{args.out} does not match this APK")
        print(f"verified {args.out}")
    else:
        Path(args.out).write_text(json.dumps(remap, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
