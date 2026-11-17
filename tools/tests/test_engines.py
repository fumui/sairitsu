import gzip
import json

from sairitsu_tools import engines


def test_committed_builds_are_current():
    assert engines.write(check=True) == [], "run: python -m sairitsu_tools build-engines"


def test_staleness_ignores_how_the_gzip_was_compressed():
    # Another zlib build compresses the same bytes differently; that must not count as stale.
    data = b'{"archetypes":[]}' * 50
    assert engines._same_content(gzip.compress(data, 1, mtime=0), gzip.compress(data, 9, mtime=0))
    assert not engines._same_content(gzip.compress(data, mtime=0), gzip.compress(data + b" ", mtime=0))


def test_build_is_deterministic():
    path = engines.SOURCES / "preprocess_order.py"
    assert engines.build(path) == engines.build(path)


def test_every_engine_has_watch_rom_and_level():
    files = engines.build_all()
    for path in engines.SOURCES.glob("*.py"):
        watch = json.loads(gzip.decompress(files[f"{path.stem}.watch.json.gz"]))
        level = json.loads(gzip.decompress(files[f"{path.stem}.level.json.gz"]))
        assert len(gzip.decompress(files[f"{path.stem}.rom.gz"])) % 4 == 0
        names = {a["name"] for a in watch["archetypes"]}
        assert {e["archetype"] for e in level["entities"]} <= names
