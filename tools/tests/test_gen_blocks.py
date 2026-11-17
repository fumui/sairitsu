from pathlib import Path

import pytest

import os

from sairitsu_tools import gen_blocks

WIKI = Path(os.environ.get("SAIRITSU_WIKI", "wiki"))
needs_wiki = pytest.mark.skipif(not (WIKI / "watch-blocks__entity-input.txt").exists(),
                                reason="set SAIRITSU_WIKI to a mirror of the wiki's block pages")
VM_DIR = Path(__file__).resolve().parents[2] / "runtime" / "src" / "vm"

PAGE = """Entity Input ​
Intro.
Identifier ​
tsconst EntityInputId = 4004
Values ​
 |  | Index | Initial Value | Description
 | 0 | 0 | Target time of the entity
 | 1 | -1 | Assigned bucket
 | 2 | 0 | Bucket value
Access ​
 |  | Callback | Read | Write
 | preprocess | ✔ | ✔
 | updateSpawn | ✔ | ❌
Remarks ​
Text."""


def test_parse_page():
    b = gen_blocks.parse_wiki_page(PAGE)
    assert (b.name, b.id, b.scope, b.count, b.size) == ("Entity Input", 4004, "PerEntity", "Fixed", 3)
    assert b.init == [0.0, -1.0, 0.0]
    assert b.read == {"preprocess", "updateSpawn"} and b.write == {"preprocess"}


def test_disagreement_names_the_pair():
    class Py(int):
        name = "EntityInput"
        readable = {"preprocess", "updateSpawn"}
        writable = {"preprocess", "updateSpawn"}

    with pytest.raises(gen_blocks.BlockError, match=r"Entity Input \(4004\) write: wiki-only \[\], sonolus.py-only \['updateSpawn'\]"):
        gen_blocks.merge("watch", [gen_blocks.parse_wiki_page(PAGE)], [Py(4004)])


@needs_wiki
def test_gen_blocks_matches_wiki():
    # Raises BlockError naming every pair if the wiki and sonolus.py disagree on access.
    generated = gen_blocks.generate(WIKI)
    assert set(generated) == {"blocks_watch.inc", "blocks_play.inc"}
    assert '{4004, "Entity Input", 3, BlockScope::PerEntity' in generated["blocks_watch.inc"]
    assert '{1003, "Runtime Skin Transform", 16' in generated["blocks_play.inc"]
    assert gen_blocks.write(WIKI, VM_DIR, check=True) == [], "committed tables are stale: run gen-blocks"
