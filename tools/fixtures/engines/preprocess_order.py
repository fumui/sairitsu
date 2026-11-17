"""Mini engine: callback order during Watch preparation.

Every callback logs a code with DebugLog: preprocess logs the entity index, spawnTime 100 + index, despawnTime
200 + index. The runtime records the logged values in call order, so the C++ test reads the order back.
spawnTime returns 10 + index and despawnTime 20 + index.
"""

from sonolus.script.archetype import WatchArchetype, callback
from sonolus.script.debug import debug_log
from sonolus.script.engine import WatchMode
from sonolus.script.runtime import time


class First(WatchArchetype):
    name = "First"

    @callback(order=2)
    def preprocess(self):
        debug_log(self.index)

    @callback(order=-1)
    def spawn_time(self) -> float:
        debug_log(100 + self.index)
        return 10 + self.index

    def despawn_time(self) -> float:
        debug_log(200 + self.index)
        return 20 + self.index


class Second(WatchArchetype):
    name = "Second"

    @callback(order=1)
    def preprocess(self):
        debug_log(self.index)

    @callback(order=1)
    def spawn_time(self) -> float:
        debug_log(100 + self.index)
        return 10 + self.index

    @callback(order=-2)
    def despawn_time(self) -> float:
        debug_log(200 + self.index)
        return 20 + self.index


class Silent(WatchArchetype):
    """No callbacks at all: spawn and despawn time default to 0."""

    name = "Silent"


def update_spawn() -> float:
    return time()


WATCH = WatchMode(archetypes=[First, Second, Silent], update_spawn=update_spawn)

LEVEL = {
    "bgmOffset": 0,
    "entities": [
        {"archetype": "First", "data": []},
        {"archetype": "Second", "data": []},
        {"archetype": "First", "data": []},
        {"archetype": "Silent", "data": []},
        {"archetype": "Second", "data": []},
    ],
}
