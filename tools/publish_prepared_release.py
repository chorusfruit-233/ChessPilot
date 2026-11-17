"""One-time delivery of the already signed and verified public 0.6.2 APK.

Only public release bytes transit through Git data blobs. No signing key or
signing password is transferred to this runner or stored in the repository.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.parse
import urllib.request

REPO = "chorusfruit-233/ChessPilot"
COMMIT = "b083305bdcf6383ba96fe5ae5f93f3d59cae0002"
RUN_ID = 44155209123
RELEASE_ID = 449082443
TAG = "v0.6.2"
TOKEN = os.environ["GH_TOKEN"]
API = f"https://api.github.com/repos/{REPO}"


def request(url, method="GET", data=None, content_type="application/json"):
    if data is not None and not isinstance(data, bytes):
        data = json.dumps(data).encode()
    request = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": f"Bearer {TOKEN}", "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28", "Content-Type": content_type,
        "User-Agent": "ChessPilot-release-publisher",
    })
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.load(response)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


prepared = Path(".release-public")
ci = Path("ci-artifacts")
info = json.loads((prepared / "BUILD_INFO.json").read_text())
manifest = json.loads((prepared / "signed-apk-blobs.json").read_text())
assert info["commit"] == COMMIT
run = request(f"{API}/actions/runs/{RUN_ID}")
assert run["head_sha"] == COMMIT and run["conclusion"] == "success"
tag_object = request(f"{API}/git/ref/tags/{TAG}")["object"]
while tag_object["type"] == "tag":
    tag_object = request(f"{API}/git/tags/{tag_object['sha']}")["object"]
assert tag_object["sha"] == COMMIT
draft = request(f"{API}/releases/{RELEASE_ID}")
assert draft["tag_name"] == TAG and draft["draft"]

out = Path("publish-files")
out.mkdir(exist_ok=True)
apk = out / manifest["name"]
assert apk.name == "ChessPilot-0.6.2-arm64-v8a.apk"
with apk.open("wb") as stream:
    for index, chunk in enumerate(manifest["chunks"]):
        blob = request(f"{API}/git/blobs/{chunk['sha']}")
        assert blob["encoding"] == "base64"
        data = base64.b64decode(blob["content"])
        assert len(data) == chunk["size"]
        assert hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == chunk["sha"]
        stream.write(data)
        print(f"Received public APK chunk {index + 1}/{len(manifest['chunks'])}", flush=True)
assert apk.stat().st_size == manifest["size"]
assert sha(apk) == manifest["sha256"] == info["published_apk"]["sha256"]
assert sha(ci / info["unsigned_apk"]["name"]) == info["unsigned_apk"]["sha256"]
source = ci / info["source_archive"]["name"]
assert sha(source) == info["source_archive"]["sha256"]
shutil.copyfile(source, out / source.name)
for name in ["BUILD_INFO.json", "SHA256SUMS"]:
    shutil.copyfile(prepared / name, out / name)
subprocess.run(["sha256sum", "--check", "SHA256SUMS"], cwd=out, check=True)
subprocess.run(["python3", "tools/check_unified_apk.py", str(apk)], check=True)

upload_url = draft["upload_url"].split("{")[0]
assert upload_url == f"https://uploads.github.com/repos/{REPO}/releases/{RELEASE_ID}/assets"
existing = {asset["name"]: asset for asset in draft["assets"]}
files = sorted(out.iterdir())
for path in files:
    if path.name in existing:
        asset = existing[path.name]
        assert asset["digest"] == "sha256:" + sha(path)
    else:
        asset = request(upload_url + "?name=" + urllib.parse.quote(path.name),
                        method="POST", data=path.read_bytes(),
                        content_type="application/octet-stream")
    assert asset["state"] == "uploaded" and asset["size"] == path.stat().st_size
    assert asset["digest"] == "sha256:" + sha(path)
    print("Uploaded and verified:", path.name, flush=True)

draft = request(f"{API}/releases/{RELEASE_ID}")
assert {asset["name"] for asset in draft["assets"]} == {path.name for path in files}
release = request(f"{API}/releases/{RELEASE_ID}", method="PATCH", data={
    "draft": False, "prerelease": False, "make_latest": "true",
})
assert not release["draft"]
print("Published:", release["html_url"], flush=True)
