"""Mini engine: entities made by the Spawn function during preprocess join the spawn queue.

Each Emitter spawns two Effects (start = its `origin` and `origin` + 1, length 0.5) into Entity Memory. Effect has no
level entities; its spawnTime (order -1, so it runs before Emitter's) logs 100 + start with DebugLog and
returns start; its initialize logs 300 + start. Each Emitter's first updateSequential spawns one more Effect
(start = the frame's time - 0.5, length 1), and the Emitter at 5 spawns an Effect at 20 (length 1) from its
despawnTime: both join the spawn queue on the next update cycle. Spin loops forever in preprocess when its `forever`
import is nonzero (the fault test adds one).
"""

from sonolus.script.archetype import WatchArchetype, callback, entity_memory, imported
from sonolus.script.debug import debug_log
from sonolus.script.engine import WatchMode
from sonolus.script.runtime import time


class Effect(WatchArchetype):
    name = "Effect"

    start: float = entity_memory()
    length: float = entity_memory()

    def preprocess(self):
        debug_log(-1)  # never runs: spawned entities are not preprocessed

    @callback(order=-1)
    def spawn_time(self) -> float:
        debug_log(100 + self.start)
        return self.start

    def despawn_time(self) -> float:
        return self.start + self.length

    def initialize(self):
        debug_log(300 + self.start)


class Emitter(WatchArchetype):
    name = "Emitter"

    origin: float = imported()
    fired: bool = entity_memory()

    def preprocess(self):
        Effect.spawn(start=self.origin, length=0.5)
        Effect.spawn(start=self.origin + 1, length=0.5)

    def spawn_time(self) -> float:
        debug_log(100 + self.origin)
        return self.origin

    def despawn_time(self) -> float:
        if self.origin == 5:
            Effect.spawn(start=20, length=1)
        return self.origin + 4

    def update_sequential(self):
        if not self.fired:
            Effect.spawn(start=time() - 0.5, length=1)
            self.fired = True


class Spin(WatchArchetype):
    name = "Spin"

    forever: float = imported()
    count: float = entity_memory()

    def preprocess(self):
        while self.forever != 0:
            self.count += 1


def update_spawn() -> float:
    return time()


WATCH = WatchMode(archetypes=[Effect, Emitter, Spin], update_spawn=update_spawn)

LEVEL = {
    "bgmOffset": 0,
    "entities": [
        {"archetype": "Emitter", "data": [{"name": "origin", "value": 2}]},
        {"archetype": "Emitter", "data": [{"name": "origin", "value": 5}]},
    ],
}
