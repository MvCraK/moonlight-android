"""Publish a verified APK, then advance only its channel's update manifest."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import zipfile


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


channel = os.environ["CHANNEL"]
branch = {"axixi": "master", "vplus": "vplus"}[channel]
package = f"com.mvcrak.moonlight.{channel}"
repo = os.environ["GITHUB_REPOSITORY"]
commit = os.environ["GITHUB_SHA"]
code = int(os.environ["CODE"])
tag = f"{channel}-{code}"
apk = Path(f"{tag}.apk")
sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"


def verify(path):
    certs = run(sdk / "apksigner", "verify", "--print-certs", path)
    if re.findall(r"^Signer #\d+ certificate SHA-256 digest: (\w+)$", certs, re.M) != [os.environ["CERTIFICATE"]]:
        raise ValueError("Unexpected signing certificate")
    badging = run(sdk / "aapt", "dump", "badging", path)
    attrs = dict(re.findall(r"(\w+)='([^']*)'", badging.splitlines()[0]))
    if attrs["name"] != package or int(attrs["versionCode"]) != code:
        raise ValueError("Unexpected package or version")
    expected_label = {"axixi": "月光·阿西西", "vplus": "Moonlight V+"}[channel]
    if f"application-label:'{expected_label}'" not in badging.splitlines():
        raise ValueError("Unexpected application label")
    ss = "libss_network" if channel == "axixi" else "libvplus_ss"
    with zipfile.ZipFile(path) as archive:
        for abi in ("arm64-v8a", "armeabi-v7a"):
            for lib in ("libmoonlight-core.so", ss + ".so", ss + "_jni.so"):
                if archive.getinfo(f"lib/{abi}/{lib}").file_size == 0:
                    raise ValueError(f"Empty native library: {lib}")
    if channel == "vplus":
        run("python3", ".github/release/verify-release-apk.py", path, attrs["versionName"], str(code))
    return attrs["versionName"]


def publish():
    if os.environ["GITHUB_REF_NAME"] != branch or repo != "MvCraK/moonlight-android":
        raise ValueError("Channel/ref mismatch")
    verify(apk)
    # A tag is immutable. A retry may resume after the release or asset was created.
    refs = run("git", "ls-remote", "--tags", "origin", f"refs/tags/{tag}")
    if refs and refs.split()[0] != commit:
        raise ValueError("Tag belongs to another source commit")
    if not refs:
        run("git", "push", "origin", f"{commit}:refs/tags/{tag}")
    if subprocess.run(["gh", "release", "view", tag], stdout=subprocess.DEVNULL).returncode:
        run("gh", "release", "create", tag, "--verify-tag", "--draft", "--title", tag,
            "--notes", f"{channel} · {commit[:12]}")
    release = json.loads(run("gh", "release", "view", tag, "--json", "assets,isDraft"))
    if not any(asset["name"] == apk.name for asset in release["assets"]):
        if not release["isDraft"]:
            raise ValueError("Published release is missing its APK")
        run("gh", "release", "upload", tag, apk)
    # Read back the actual hosted bytes, including on retries.
    with tempfile.TemporaryDirectory() as tmp:
        run("gh", "release", "download", tag, "--dir", tmp, "--pattern", apk.name)
        remote_apk = Path(tmp) / apk.name
        version = verify(remote_apk)
        entry = dict(versionCode=code, versionName=version, commit=commit, size=remote_apk.stat().st_size,
                     sha256=hashlib.sha256(remote_apk.read_bytes()).hexdigest(),
                     apkUrl=f"https://github.com/{repo}/releases/download/{tag}/{apk.name}",
                     notes=run("git", "log", "-1", "--format=%s", commit))
    if release["isDraft"]:
        run("gh", "release", "edit", tag, "--draft=false", "--latest=false")
    write_manifest(entry)


def write_manifest(entry):
    # Separate ephemeral index so the data branch has no Android source files.
    run("git", "config", "user.name", "github-actions")
    run("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "updates"
        run("git", "worktree", "add", "--detach", "--no-checkout", work, "HEAD")
        for attempt in range(6):
            ref = run("git", "ls-remote", "--heads", "origin", "refs/heads/updates")
            if ref:
                run("git", "fetch", "--no-tags", "origin", "refs/heads/updates", cwd=work)
                run("git", "checkout", "--force", "--detach", "FETCH_HEAD", cwd=work)
            else:
                run("git", "checkout", "--orphan", "initial-updates", cwd=work)
                run("git", "read-tree", "--empty", cwd=work)
            path = work / f"{channel}.json"
            previous = json.loads(path.read_text()) if path.exists() else {}
            latest = previous.get("latest")
            if latest and latest["versionCode"] >= code:
                if latest["versionCode"] == code and latest != entry:
                    raise ValueError("Conflicting published version")
                return
            history = ([latest] + previous.get("history", []))[:50] if latest else []
            path.write_text(json.dumps(dict(packageName=package, latest=entry, history=history),
                                       indent=2, ensure_ascii=False) + "\n")
            run("git", "add", path.name, cwd=work)
            run("git", "commit", "-m", f"Publish {tag}", cwd=work)
            if subprocess.run(["git", "push", "origin", "HEAD:refs/heads/updates"], cwd=work).returncode == 0:
                return
            time.sleep(attempt + 1)
        raise RuntimeError("Unable to advance update manifest")


if __name__ == "__main__":
    publish()
