"""Mini engine: skin sprites drawn from sequential and parallel callbacks, merged in a fixed order.

Skin sprites: "note" (id 0), "lane" (id 1) and "absent" (id 2, which the C++ test's skin leaves out).
Sky (entity 0, updateSequential) draws "lane" once with DrawCurvedBT at z 5. Each Drawer (entities 1-12) draws "note"
twice in updateParallel: the quad (index, y) to (index + 1, y + 1) for y = 0 and 1, the first with z and alpha from
random() (so the RNG takes part in the thread-count comparison), the second with z (1, 2, 3, 4) and alpha 0.5; then
"absent" and the sprite with the non-integer id `half` (0.5), which name nothing and draw nothing.
Drawer's preprocess (entity 1 only) logs HasSkinSprite of ids 0, 1, 2 and 0.5, so a test can see that preparation
answers from the level's skin.
"""

import random

from sonolus.script.archetype import WatchArchetype, imported
from sonolus.script.debug import debug_log
from sonolus.script.engine import WatchMode
from sonolus.script.quad import Quad
from sonolus.script.runtime import time
from sonolus.script.sprite import Sprite, skin, sprite
from sonolus.script.vec import Vec2


@skin
class Skin:
    note: Sprite = sprite("note")
    lane: Sprite = sprite("lane")
    absent: Sprite = sprite("absent")


def cell(x: float, y: float) -> Quad:
    return Quad(bl=Vec2(x, y), tl=Vec2(x, y + 1), tr=Vec2(x + 1, y + 1), br=Vec2(x + 1, y))


class Sky(WatchArchetype):
    name = "Sky"

    def spawn_time(self) -> float:
        return 0

    def despawn_time(self) -> float:
        return 100

    def update_sequential(self):
        Skin.lane.draw_curved_bt(cell(-2, -1), cp1=Vec2(0.25, -1), cp2=Vec2(-0.25, 1), n=8, z=5)


class Drawer(WatchArchetype):
    name = "Drawer"

    half: float = imported(default=0.5)

    def preprocess(self):
        if self.index == 1:
            debug_log(Skin.note.is_available)
            debug_log(Skin.lane.is_available)
            debug_log(Skin.absent.is_available)
            debug_log(Sprite(self.half).is_available)

    def spawn_time(self) -> float:
        return 0

    def despawn_time(self) -> float:
        return 100

    def update_parallel(self):
        r = random.random()
        Skin.note.draw(cell(self.index, 0), z=r, a=r)
        Skin.note.draw(cell(self.index, 1), z=(1, 2, 3, 4), a=0.5)
        Skin.absent.draw(cell(self.index, 2))
        Sprite(self.half).draw(cell(self.index, 3))


def update_spawn() -> float:
    return time()


WATCH = WatchMode(archetypes=[Sky, Drawer], skin=Skin, update_spawn=update_spawn)

LEVEL = {
    "bgmOffset": 0,
    "entities": [{"archetype": "Sky", "data": []}] + [{"archetype": "Drawer", "data": []} for _ in range(12)],
}
