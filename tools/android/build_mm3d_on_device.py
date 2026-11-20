#!/usr/bin/env python3
"""Prepare a private MM3D installation and build its Android ARM64 module.

The caller supplies a Python environment with Capstone and an Android C++
compiler. All ROM-derived output stays under the private workspace.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/mm3d/package"))
sys.path.insert(0, str(ROOT / "tools/oot3d/native_a32_runtime"))
import mm3d_setup as setup
from build_process_manifest import build_manifest
from prepare_mm3d_data import prepare

HOST = ROOT / "tools/oot3d/native_game_runtime"
A32 = ROOT / "tools/oot3d/native_a32_runtime"
SUPPORT = (
    HOST / "oot3d_native_a32_memory.cpp",
    HOST / "oot3d_native_a32_memory_bus_defaults.cpp",
    A32 / "upstream/recomp/a32_vfp_binary64.cpp",
    A32 / "oot3d_native_a32_vfp_ops.cpp",
    A32 / "upstream/recomp/a32_vfp_transport.cpp",
)


class BuildCancelled(Exception):
    pass


class BuildCommands:
    def __init__(self, cancel_file: Path | None):
        self.cancel_file = cancel_file

    def check(self) -> None:
        if self.cancel_file is not None and self.cancel_file.exists():
            raise BuildCancelled()

    def run(self, arguments, **kwargs) -> None:
        self.check()
        kwargs.setdefault("cwd", ROOT)
        with subprocess.Popen(arguments, start_new_session=True, **kwargs) as process:
            try:
                while True:
                    self.check()
                    try:
                        status = process.wait(timeout=0.2)
                        break
                    except subprocess.TimeoutExpired:
                        continue
            except BaseException:
                if process.poll() is None:
                    try:
                        os.killpg(process.pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                    try:
                        process.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                        process.wait()
                raise
        self.check()
        if status:
            raise subprocess.CalledProcessError(status, arguments)


def prepare_sources(rom: Path, workspace: Path, commands: BuildCommands) -> tuple[Path, Path]:
    install = workspace / "extracted"
    game, work = install / "game", install / "work"
    generated = workspace / "generated"
    stage = workspace / "data"
    ready = workspace / "prepared"
    if ready.is_file():
        return generated, stage

    for incomplete in (install, generated, stage):
        if incomplete.exists():
            shutil.rmtree(incomplete)
    setup.step("Extracting and validating the ROM")
    setup.extract(rom, game)
    commands.check()
    if setup.sha256(game / "code.bin") not in setup.SUPPORTED:
        raise ValueError("Expected decrypted Majora's Mask 3D USA v1.1 code.")
    if setup.sha256(game / "romfs.bin") not in setup.CLEAN_ROMFS:
        raise ValueError("Expected an unmodified USA v1.1 RomFS.")

    setup.prepare_work(work)
    setup.translate(work, game, run=commands.run)
    setup.step("Generating C++ sources")
    commands.run([sys.executable, ROOT / "tools/mm3d/mm3d_emit_title_sources.py", work, generated])
    setup.step("Preparing game data")
    manifest = build_manifest(game / "exheader.bin", game / "code.bin", game / "romfs.bin")
    for key in ("code_bin_path", "exheader_path", "romfs_image_path"):
        manifest["source"][key] = "game/" + Path(manifest["source"][key]).name
    manifest["title"] = setup.TITLE
    (install / "resources").mkdir()
    (install / "process-manifest.json").write_text(json.dumps(manifest) + "\n", encoding="utf-8")
    prepare(install, stage, None)
    commands.check()
    shutil.rmtree(install)
    ready.touch()
    return generated, stage


def compile_title(generated: Path, stage: Path, compiler: list[str], headers: Path,
                  jobs: int, commands: BuildCommands) -> None:
    includes = (generated, headers, HOST, A32, A32 / "mass_cpp",
                A32 / "upstream", A32 / "upstream/recomp")
    flags = ["-std=gnu++20", "-DANDROID", "-fPIC", "-fvisibility=hidden", "-fvisibility-inlines-hidden",
             "-ffp-model=strict", "-ffunction-sections", "-fdata-sections", "-DNDEBUG",
             *[f"-I{path}" for path in includes]]
    manifest = json.loads((generated / "TITLE_SOURCE_MANIFEST.json").read_text(encoding="utf-8"))
    sources = [generated / name for name in sorted(manifest["files"]) if name.endswith(".cpp")]
    sources += [HOST / "triaevum_title_whole_aot_plugin.cpp", *SUPPORT]
    object_dir = stage.parent / "objects"
    object_dir.mkdir(exist_ok=True)
    objects = [object_dir / f"{source.name}.o" for source in sources]

    def compile_one(pair: tuple[Path, Path]) -> Path:
        source, target = pair
        commands.check()
        if target.is_file():
            return target
        options = (["-O2", "-fno-stack-protector"] if source in SUPPORT else
                   ["-O1", "-DOOT3D_NATIVE_GENERATED_WHOLE_AOT=1"])
        tmp = target.with_suffix(".tmp")
        commands.run([*compiler, *flags, *options, "-c", source, "-o", tmp])
        tmp.replace(target)
        return target

    setup.step("Compiling C++ sources")
    with ThreadPoolExecutor(max_workers=jobs) as executor:
        futures = [executor.submit(compile_one, pair) for pair in zip(sources, objects)]
        try:
            for i, future in enumerate(as_completed(futures), 1):
                print(f"[{i}/{len(objects)}] {future.result().name}", flush=True)
        except BaseException:
            for future in futures:
                future.cancel()
            raise

    output = stage / "libtriaevum_title_aot.so"
    tmp = output.with_suffix(".tmp.so")
    setup.step("Linking the game module")
    commands.run([*compiler, "-shared", "-Wl,--no-undefined", "-Wl,--gc-sections",
                    "-Wl,-z,max-page-size=16384", "-Wl,-soname,libtriaevum_title_aot.so",
                    "-o", tmp, *objects, "-latomic", "-lm"])
    tmp.replace(output)
    print(f"MM3D module ready: {output}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--cxx", type=Path, required=True)
    parser.add_argument("--cxx-arg", action="append", default=[])
    parser.add_argument("--json-include", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=2, choices=range(1, 5))
    parser.add_argument("--bundle-id", default="")
    parser.add_argument("--cancel-file", type=Path)
    args = parser.parse_args()
    root = args.workspace.resolve()
    if root.is_relative_to(ROOT):
        parser.error("The workspace must be outside the tools directory.")
    compiler = [str(args.cxx.resolve()), *args.cxx_arg]
    commands = BuildCommands(args.cancel_file)
    commands.check()
    version = subprocess.check_output([*compiler, "--version"])
    ident = setup.sha256(args.rom)
    commands.check()
    key = hashlib.sha256(version + repr(compiler).encode() + ident.encode()
                         + args.bundle_id.encode()).hexdigest()
    root.mkdir(parents=True, exist_ok=True)
    with (root / "compile.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        workspace = root / key
        generated, stage = prepare_sources(args.rom.resolve(), workspace, commands)
        compile_title(generated, stage, compiler, args.json_include.resolve(), args.jobs, commands)
        commands.check()
        ready = root / "ready.tmp"
        ready.write_text(key + "\n", encoding="ascii")
        ready.replace(root / "ready.txt")


if __name__ == "__main__":
    try:
        main()
    except BuildCancelled:
        print("MM3D build cancelled.", flush=True)
        sys.exit(130)
