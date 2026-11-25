"""Cross-compile the Shadowsocks libraries with the same NDK/API as moonlight-core.

Usage: build-android.py <ndk> <cargo-target-dir> <libs-out-dir> <abi>...
Produces <libs-out-dir>/<abi>/{libvplus_ss.so,libvplus_ss_jni.so}.
"""
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

crate = Path(__file__).resolve().parent
hook_source = crate.parent / "jni" / "ss_hook.c"
ndk, cargo_dir, libs = map(Path, sys.argv[1:4])
host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64", "Windows": "windows-x86_64"}[platform.system()]
tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
targets = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android"),
    "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi"),
    "x86": ("i686-linux-android", "i686-linux-android"),
}
page = "-Wl,-z,max-page-size=16384"
for abi in sys.argv[4:]:
    target, clang = targets[abi]
    cc = str(tools / (clang + "22-clang" + (".cmd" if os.name == "nt" else "")))
    if shutil.which("rustup"):
        # Keeps CI workflows untouched: install the std for this target on demand.
        subprocess.run(["rustup", "target", "add", target], check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    env = dict(os.environ, CARGO_TARGET_DIR=str(cargo_dir),
               RUSTFLAGS=f"-C link-arg={page} -C link-arg=-Wl,-soname,libvplus_ss.so")
    env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER"] = cc
    env["CC_" + target.replace("-", "_")] = cc
    env["AR_" + target.replace("-", "_")] = str(tools / "llvm-ar")
    subprocess.run(["cargo", "build", "--locked", "--release", "--target", target], cwd=crate, env=env, check=True)
    out = libs / abi
    out.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cargo_dir / target / "release/libvplus_ss.so", out / "libvplus_ss.so")
    subprocess.run([cc, "-shared", "-fPIC", "-O2", "-Wall", "-Werror", page,
                    "-Wl,-soname,libvplus_ss_jni.so", "-o", str(out / "libvplus_ss_jni.so"),
                    str(hook_source), "-L" + str(out), "-lvplus_ss", "-ldl"], check=True)
