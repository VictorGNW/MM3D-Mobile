#!/usr/bin/env python3
"""Create the local-build asset from an Android ARM64 toolchain data directory."""
import argparse
from pathlib import Path
import subprocess
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = "tools/oot3d/native_a32_runtime"
HOST = "tools/oot3d/native_game_runtime"


def repository_files():
    # Build the resource archive from tracked source, never a translated-title
    # workspace. The two Android entry points are included explicitly.
    for path in ("tools/android/prepare_mm3d_data.py", "tools/android/build_mm3d_on_device.py",
                 "tools/oot3d/operational_inputs.json"):
        yield Path(path)
    raw = subprocess.check_output(["git", "ls-files", "-z", "--", "tools/mm3d", RUNTIME, HOST], cwd=ROOT)
    for name in raw.decode().split("\0"):
        if not name:
            continue
        path = Path(name)
        if name.startswith("tools/mm3d/"):
            if path.suffix in (".py", ".json", ".xz") or "/package/recipe/" in name:
                yield path
        elif name.startswith(RUNTIME + "/") and path.suffix in (".py", ".cpp", ".h", ".hpp", ".inc"):
            yield path
        elif name.startswith(HOST + "/") and path.suffix in (".cpp", ".h", ".hpp", ".inc"):
            yield path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--toolchain-data", type=Path, required=True,
                        help="Directory containing python/, sysroot/, clang-resource/ and include/")
    parser.add_argument("--assets", type=Path, required=True,
                        help="Gradle assets directory outside the checkout")
    args = parser.parse_args()
    data = args.toolchain_data.resolve()
    assets = args.assets.resolve()
    if assets.is_relative_to(ROOT) or assets.is_relative_to(data):
        parser.error("Use an assets directory outside the checkout and toolchain data.")
    dirs = ("python", "sysroot", "clang-resource", "include")
    for name in dirs:
        if not (data / name).is_dir():
            parser.error(f"Missing {data / name}")
    if not (data / "include/nlohmann/json.hpp").is_file():
        parser.error("Missing nlohmann/json.hpp in the toolchain include directory.")
    assets.mkdir(parents=True, exist_ok=True)
    output = assets / "mm3d-build-tools.zip"
    temporary = output.with_suffix(".tmp")
    with ZipFile(temporary, "w", ZIP_DEFLATED, compresslevel=3) as archive:
        for path in sorted(set(repository_files())):
            archive.write(ROOT / path, Path("repo") / path)
        for name in dirs:
            for path in sorted((data / name).rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(data))
    temporary.replace(output)
    print(output)


if __name__ == "__main__":
    main()
