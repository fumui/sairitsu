"""Builds the mini test engines (tools/fixtures/engines/*.py) into runtime/tests/fixtures/engines.

Each engine module defines WATCH (a sonolus.py WatchMode) and LEVEL (level data as JSON). The build writes
`<name>.watch.json.gz` (engine watch data), `<name>.rom.gz` (engine ROM, as sonolus.py packages it: gzipped
little-endian float32; sonolus.py always puts its NaN/Inf constants there) and `<name>.level.json.gz` (level data),
all gzip with mtime 0, so a rebuild of unchanged sources is byte-identical.
"""

import gzip
import importlib.util
import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SOURCES = REPO / "tools" / "fixtures" / "engines"
OUT = REPO / "runtime" / "tests" / "fixtures" / "engines"


def _load(path: Path):
    spec = importlib.util.spec_from_file_location(f"fixture_engine_{path.stem}", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _gzip_json(value) -> bytes:
    text = json.dumps(value, separators=(",", ":"), allow_nan=False).encode("utf-8")
    return gzip.compress(text, mtime=0)


def build(path: Path) -> dict[str, bytes]:
    """The fixture files of one engine module, by file name."""
    from sonolus.build.engine import package_engine, unpackage_data
    from sonolus.script.engine import EngineData
    from sonolus.script.project import BuildConfig

    module = _load(path)
    config = BuildConfig(build_play=False, build_preview=False, build_tutorial=False)
    packaged = package_engine(EngineData(watch=module.WATCH), config)
    return {
        f"{path.stem}.watch.json.gz": _gzip_json(unpackage_data(packaged.watch_data)),
        f"{path.stem}.rom.gz": packaged.rom,
        f"{path.stem}.level.json.gz": _gzip_json(module.LEVEL),
    }


def build_all(sources: Path = SOURCES) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for path in sorted(sources.glob("*.py")):
        files.update(build(path))
    return files


def write(out: Path = OUT, sources: Path = SOURCES, check: bool = False) -> list[str]:
    """Writes every fixture (or with `check`, writes nothing). Returns the names that were missing or stale."""
    stale = []
    for name, data in build_all(sources).items():
        target = out / name
        if target.exists() and target.read_bytes() == data:
            continue
        stale.append(name)
        if not check:
            out.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    return stale
