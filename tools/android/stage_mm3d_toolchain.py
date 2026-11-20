#!/usr/bin/env python3
"""Stage a relocatable Android ARM64 toolchain for the MM3D debug APK.

Inputs are locally extracted Termux packages, the matching Capstone Python
binding, an Android NDK for Linux x86-64, and nlohmann/json headers.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil
import subprocess
import sys

SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}


def stage(prefix: Path, capstone_python: Path, ndk: Path, patch: Path,
          json_include: Path, output: Path) -> None:
    native_dir = output / "jniLibs/arm64-v8a"
    data = output / "data"
    native_dir.mkdir(parents=True)
    data.mkdir()
    staged: set[Path] = set()

    def copy_elf(source: Path, dest: Path, set_soname: bool = False) -> None:
        if dest in staged:
            return
        staged.add(dest)
        dest.parent.mkdir(parents=True, exist_ok=True)
        info = subprocess.check_output(["readelf", "-h", "-dW", source], text=True)
        if not re.search(r"Machine:\s+AArch64\b", info):
            raise ValueError(f"Expected an Android ARM64 ELF: {source}")
        shutil.copyfile(source, dest)
        dest.chmod(0o755)
        options = ["--set-soname", dest.name] if set_soname else []
        for needed in re.findall(r"\(NEEDED\).*?\[(.*?)\]", info):
            if needed in SYSTEM_LIBS:
                continue
            library = prefix / "lib" / needed
            if not library.is_file():
                raise FileNotFoundError(f"{source} requires {needed}, missing from the Termux prefix")
            # ctypes calls dlopen() on CPython's original library name.
            name = needed if re.fullmatch(r"libpython3\.\d+\.so", needed) else (
                "libmm3d_dep_" + re.sub(r"[^a-zA-Z0-9_]", "_", needed) + ".so")
            if name != needed:
                options.extend(("--replace-needed", needed, name))
            copy_elf(library, native_dir / name, set_soname=True)
        subprocess.run([patch, "--page-size", "16384", "--set-rpath", "$ORIGIN", *options, dest], check=True)

    clang = (prefix / "bin/clang").resolve(strict=True)
    python = (prefix / "bin/python3").resolve(strict=True)
    copy_elf(clang, native_dir / "libmm3d_clang.so")
    copy_elf(prefix / "bin/lld", native_dir / "libmm3d_lld_driver.so")
    copy_elf(python, native_dir / "libmm3d_python.so")
    copy_elf(prefix / "lib/libcapstone.so", native_dir / "libcapstone.so", set_soname=True)

    stdlib = prefix / "lib" / python.name
    python_home = data / "python/lib" / python.name
    shutil.copytree(stdlib, python_home, ignore=shutil.ignore_patterns(
        "__pycache__", "*.so", "test", "tests", "ensurepip", "tkinter"))
    for module in sorted((stdlib / "lib-dynload").glob("*.so")):
        copy_elf(module, python_home / "lib-dynload" / module.name)
    shutil.copytree(capstone_python, python_home / "site-packages/capstone",
                    ignore=shutil.ignore_patterns("__pycache__", "lib", "include"))

    llvm = ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    shutil.copytree(llvm / "sysroot/usr/include", data / "sysroot/usr/include")
    libs = llvm / "sysroot/usr/lib/aarch64-linux-android"
    sysroot_libs = data / "sysroot/usr/lib/aarch64-linux-android"
    shutil.copytree(libs / "29", sysroot_libs / "29")
    for library in sorted(libs.iterdir()):
        if library.is_file():
            shutil.copy2(library, sysroot_libs / library.name)
    clang_version = clang.name.removeprefix("clang-")
    resources = llvm / "lib/clang" / clang_version
    shutil.copytree(resources / "include", data / "clang-resource/include")
    for library in sorted((resources / "lib/linux").rglob("*")):
        relative = library.relative_to(resources)
        if library.is_file() and ("aarch64" in str(relative) or library.name == "libatomic.a"):
            destination = data / "clang-resource" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(library, destination)
    shutil.copytree(json_include, data / "include")

    entry = Path(__file__).with_name("mm3d_lld_entry.c")
    subprocess.run([llvm / "bin/aarch64-linux-android29-clang", "-fPIE", "-pie",
                    "-Wl,-z,max-page-size=16384", entry,
                    "-o", native_dir / "libmm3d_lld.so"], check=True)
    subprocess.run([sys.executable, entry.with_name("pack_mm3d_build_assets.py"),
                    "--toolchain-data", data, "--assets", output / "assets"], check=True)
    print(f"Toolchain libraries: {native_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("termux-prefix", "capstone-python", "ndk", "patchelf", "json-include", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error("Use a new output directory.")
    repository = Path(__file__).resolve().parents[2]
    if output.is_relative_to(repository):
        parser.error("Keep the staged binaries outside the source repository.")
    stage(args.termux_prefix.resolve(), args.capstone_python.resolve(), args.ndk.resolve(),
          args.patchelf.resolve(), args.json_include.resolve(), output)


if __name__ == "__main__":
    main()
