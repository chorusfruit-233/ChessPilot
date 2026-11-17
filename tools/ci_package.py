#!/usr/bin/env python3
"""Package a verified CI APK with matching engine sources and build provenance.

Signing is performed separately with the existing release key. This job needs no
repository secrets and never creates a new signing identity on the CI runner.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
NET = "nn-1a298aa575a0.nnue"
NET_SHA256 = "1a298aa575a085434d29027978dc36867fe9c5bcea9376654b7a8eba1e52dfc2"


def git(directory, *args):
    return subprocess.check_output(["git", "-C", str(directory), *args], text=True).strip()


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def extract_tracked(directory, destination, archive):
    with archive.open("wb") as stream:
        subprocess.run(["git", "-C", str(directory), "archive", "--format=tar", "HEAD"],
                       stdout=stream, check=True)
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        source.extractall(destination, filter="data")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    config = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName\s*=\s*"([0-9A-Za-z.-]+)"', config).group(1)
    code = int(re.search(r"versionCode\s*=\s*(\d+)", config).group(1))
    commit = git(ROOT, "rev-parse", "HEAD")
    if os.environ.get("GITHUB_SHA") and os.environ["GITHUB_SHA"] != commit:
        raise RuntimeError("Checked-out commit does not match the GitHub Actions event")
    engine = ROOT / "app/src/main/cpp/stockfish"
    engine_commit = git(engine, "rev-parse", "HEAD")
    if engine_commit != git(engine, "rev-parse", "sf_19^{commit}"):
        raise RuntimeError("Engine source must match the sf_19 tag")
    network = engine / "src" / NET
    if sha256(network) != NET_SHA256:
        raise RuntimeError("NNUE SHA-256 mismatch")

    apk = output / f"ChessPilot-{version}-unsigned.apk"
    shutil.copyfile(ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk", apk)
    sources = output / f"ChessPilot-{version}-source.tar.gz"
    with tempfile.TemporaryDirectory(prefix="chesspilot-source-") as temporary:
        staging = Path(temporary)
        tree = staging / f"ChessPilot-{version}"
        extract_tracked(ROOT, tree, staging / "app.tar")
        extract_tracked(engine, tree / "app/src/main/cpp/stockfish", staging / "engine.tar")
        shutil.copyfile(network, tree / "app/src/main/cpp/stockfish/src" / NET)
        with tarfile.open(sources, "w:gz", compresslevel=6) as archive:
            archive.add(tree, arcname=tree.name)

    repository = os.environ.get("GITHUB_REPOSITORY")
    run = os.environ.get("GITHUB_RUN_ID")
    info = {
        "version_name": version, "version_code": code, "application_id": "com.chessassist",
        "commit": commit, "stockfish_tag": "sf_19", "stockfish_commit": engine_commit,
        "nnue": {"name": NET, "sha256": NET_SHA256},
        "unsigned_apk": {"name": apk.name, "sha256": sha256(apk)},
        "source_archive": {"name": sources.name, "sha256": sha256(sources)},
        "github_actions_run": f"https://github.com/{repository}/actions/runs/{run}"
            if repository and run else None,
        "signing": "unsigned; sign with the existing key before publishing",
    }
    provenance = output / "BUILD_INFO.json"
    provenance.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    (output / "SHA256SUMS-unsigned.txt").write_text(
        "".join(f"{sha256(path)}  {path.name}\n" for path in [apk, sources, provenance]),
        encoding="utf-8")
    print(json.dumps(info, indent=2))


if __name__ == "__main__":
    main()
