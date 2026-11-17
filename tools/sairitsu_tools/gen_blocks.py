"""Generate the per-mode block tables (runtime/src/vm/blocks_{watch,play}.inc).

Two sources must agree:
  * ids and read/write callback sets come from sonolus.py (``sonolus.backend.blocks``);
  * sizes, scope and initial values come from the engine-spec wiki's block pages, saved as text
    (``<wiki>/watch-blocks__*.txt``, ``<wiki>/play-blocks__*.txt``).
If the access tables of the two disagree, generation fails and names every pair.
"""

import re
from dataclasses import dataclass, field
from pathlib import Path

CALLBACKS = (
    "preprocess", "spawnTime", "despawnTime", "spawnOrder", "shouldSpawn",
    "initialize", "updateSequential", "touch", "updateParallel", "terminate", "updateSpawn",
)

# Dynamic (data-dependent) blocks: which constructor count sizes them.
DYNAMIC_COUNT = {
    "Level Option": "Options",
    "Level Bucket": "Buckets",
    "Archetype Life": "Archetypes",
    "Archetype Score": "Archetypes",
    "Engine Rom": "RomSize",
    "Runtime Touch Array": "Touches",
}

# Per-entity blocks that also exist for entities created by the Spawn function
# (wiki `Spawn`: those entities lack Entity Data, Input, Shared Memory and Info).
SPAWNED_HAVE = {"Entity Memory", "Entity Despawn"}

IDENTITY = [1.0 if i % 5 == 0 else 0.0 for i in range(16)]
CHECK = "✔"


class BlockError(Exception):
    pass


@dataclass
class WikiBlock:
    name: str
    id: int
    scope: str            # Global | PerEntity | EntityArray
    count: str            # Fixed | Entities | Options | ...
    size: int             # fixed total size, or the stride of a dynamic block
    init: list[float]     # length == size
    read: set[str] = field(default_factory=set)
    write: set[str] = field(default_factory=set)


def _cells(line: str) -> list[str]:
    return [c.strip() for c in line.split(" | ")]


def _initial(text: str, width: int, name: str) -> list[float]:
    text = text.strip()
    if text == "?":
        return [0.0] * width
    if "identity" in text.lower():
        if width != 16:
            raise BlockError(f"{name}: identity initial value for {width} entries")
        return list(IDENTITY)
    try:
        return [float(text)] * width
    except ValueError:
        raise BlockError(f"{name}: unreadable initial value {text!r}") from None


def parse_wiki_page(text: str) -> WikiBlock:
    lines = [l.rstrip() for l in text.splitlines()]
    name = lines[0].replace("​", "").strip()
    ident = re.search(r"const\s+\w+Id\s*=\s*(\d+)", text)
    if not ident:
        raise BlockError(f"{name}: no identifier")
    block_id = int(ident.group(1))

    def section(start: str, stop: tuple[str, ...]) -> list[str]:
        out: list[str] = []
        on = False
        for l in lines:
            if not on:
                on = l.startswith(start)
                continue
            if l.startswith(stop):
                break
            out.append(l)
        return out

    rows: list[tuple[str, str]] = []
    dynamic = False
    for l in section("Values", ("For each", "See ", "Access", "Points ", "Values are")):
        if not l.startswith(" | "):
            continue
        c = _cells(l)
        if len(c) < 4 or c[1] in ("", "Index"):
            continue
        if c[1] == "...":
            dynamic = True
            continue
        rows.append((c[1], c[2]))
    if not rows:
        raise BlockError(f"{name}: no value rows")

    parsed = []
    for spec, init in rows:
        m = re.fullmatch(r"(\d+)(?:-(\d+))?", spec)
        if not m:
            raise BlockError(f"{name}: unreadable index {spec!r}")
        a = int(m.group(1))
        b = int(m.group(2) or a)
        parsed.append((a, b, init))

    if dynamic:
        a, b, init = parsed[0]
        if a != 0:
            raise BlockError(f"{name}: dynamic block does not start at 0")
        size = b + 1
        values = _initial(init, size, name)
    else:
        size = parsed[-1][1] + 1
        values = [0.0] * size
        expect = 0
        for a, b, init in parsed:
            if a != expect:
                raise BlockError(f"{name}: index gap at {a}")
            values[a:b + 1] = _initial(init, b - a + 1, name)
            expect = b + 1

    if name.startswith("Entity ") and name.endswith(" Array"):
        scope, count = "EntityArray", "Entities"
    elif name.startswith("Entity "):
        scope, count = "PerEntity", "Fixed"
    else:
        scope, count = "Global", "Fixed"
    if scope == "EntityArray":
        if not dynamic:
            raise BlockError(f"{name}: entity array without '...' row")
    elif dynamic:
        count = DYNAMIC_COUNT.get(name, "")
        if not count:
            raise BlockError(f"{name}: dynamic block with no known count")
    elif name in DYNAMIC_COUNT:
        raise BlockError(f"{name}: expected a dynamic block")

    block = WikiBlock(name, block_id, scope, count, size, values)
    for l in section("Access", ("Remarks",)):
        if not l.startswith(" | "):
            continue
        c = _cells(l)
        if len(c) < 4 or c[1] not in CALLBACKS:
            continue
        if c[2] == CHECK:
            block.read.add(c[1])
        if c[3] == CHECK:
            block.write.add(c[1])
    if not block.read and not block.write:
        raise BlockError(f"{name}: no access table")
    return block


def load_wiki(wiki_dir: Path, mode: str) -> list[WikiBlock]:
    pages = sorted(wiki_dir.glob(f"{mode}-blocks__*.txt"))
    pages = [p for p in pages if not p.name.endswith("__overview.txt")]
    if not pages:
        raise BlockError(f"no {mode} block pages in {wiki_dir}")
    return [parse_wiki_page(p.read_text(encoding="utf-8")) for p in pages]


def python_blocks(mode: str):
    from sonolus.backend.blocks import PlayBlock, WatchBlock
    return list({"watch": WatchBlock, "play": PlayBlock}[mode])


def merge(mode: str, wiki: list[WikiBlock], py) -> list[tuple[WikiBlock, set[str], set[str]]]:
    by_id = {b.id: b for b in wiki}
    problems: list[str] = []
    out = []
    py_ids = set()
    for p in py:
        pid = int(p)
        py_ids.add(pid)
        w = by_id.get(pid)
        if w is None:
            problems.append(f"{mode}: sonolus.py block {p.name} ({pid}) has no wiki page")
            continue
        if re.sub(r"\s+", "", w.name) != p.name:
            problems.append(f"{mode} {pid}: name differs, wiki {w.name!r} vs sonolus.py {p.name!r}")
        for what, a, b in (("read", w.read, set(p.readable)), ("write", w.write, set(p.writable))):
            if a != b:
                problems.append(
                    f"{mode} {w.name} ({pid}) {what}: wiki-only {sorted(a - b)}, sonolus.py-only {sorted(b - a)}")
        out.append((w, set(p.readable), set(p.writable)))
    for w in wiki:
        if w.id not in py_ids:
            problems.append(f"{mode}: wiki block {w.name} ({w.id}) is not in sonolus.py")
    if problems:
        raise BlockError("wiki and sonolus.py disagree:\n  " + "\n  ".join(problems))
    out.sort(key=lambda t: t[0].id)
    return out


def _mask(cbs: set[str]) -> str:
    if not cbs:
        return "0"
    return " | ".join("k" + c[0].upper() + c[1:] for c in CALLBACKS if c in cbs)


def _float(v: float) -> str:
    return f"{v!r}f"


def _period(values: list[float]) -> list[float]:
    """Shortest prefix that tiles to the whole list (keeps the tables small)."""
    n = len(values)
    for p in range(1, n + 1):
        if n % p == 0 and values == values[:p] * (n // p):
            return values[:p]
    return values


def emit(mode: str, blocks) -> str:
    var = mode.capitalize()
    out = [
        f"// Generated by tools/sairitsu_tools/gen_blocks.py ({mode}). Do not edit.",
        "// Each init array is the shortest pattern that tiles over the block (or over one entity/item).",
        "// Ids and access from sonolus.py backend/blocks.py; sizes, scope and initial values from the wiki block pages.",
        "",
    ]
    for w, _, _ in blocks:
        vals = ", ".join(_float(v) for v in _period(w.init))
        out.append(f"constexpr float k{var}Init{w.id}[] = {{{vals}}};")
    out += ["", f"constexpr BlockDef k{var}Blocks[] = {{"]
    for w, r, wr in blocks:
        spawned = "true" if (w.scope == "PerEntity" and w.name in SPAWNED_HAVE) else "false"
        out.append(
            f'    {{{w.id}, "{w.name}", {w.size}, BlockScope::{w.scope}, BlockCount::{w.count}, {spawned},'
            f" {_mask(r)}, {_mask(wr)}, k{var}Init{w.id}, std::size(k{var}Init{w.id})}},")
    out.append("};")
    return "\n".join(out) + "\n"


def generate(wiki_dir: Path) -> dict[str, str]:
    result = {}
    for mode in ("watch", "play"):
        result[f"blocks_{mode}.inc"] = emit(mode, merge(mode, load_wiki(wiki_dir, mode), python_blocks(mode)))
    return result


def write(wiki_dir: Path, out_dir: Path, check: bool = False) -> list[str]:
    """Write the tables; with check, only report files that differ."""
    stale = []
    for name, text in generate(wiki_dir).items():
        path = out_dir / name
        if check:
            if not path.exists() or path.read_bytes() != text.encode("utf-8"):
                stale.append(name)
        else:
            path.write_bytes(text.encode("utf-8"))
    return stale
