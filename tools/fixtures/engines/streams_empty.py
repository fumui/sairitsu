"""Mini engine: streams read without a replay.

Probe (active in [0, 10)) logs, in updateSequential, StreamHas, StreamGetValue, StreamGetNextKey and
StreamGetPreviousKey of stream 0 at its imported `query` (5.5; level data so nothing folds it at build time).
"""

from sonolus.script.archetype import WatchArchetype, imported
from sonolus.script.debug import debug_log
from sonolus.script.engine import WatchMode
from sonolus.script.runtime import time
from sonolus.script.stream import (
    _stream_get_next_key,
    _stream_get_previous_key,
    _stream_get_value,
    _stream_has,
)


class Probe(WatchArchetype):
    name = "Probe"

    query: float = imported()

    def spawn_time(self) -> float:
        return 0

    def despawn_time(self) -> float:
        return 10

    def update_sequential(self):
        debug_log(_stream_has(0, self.query))
        debug_log(_stream_get_value(0, self.query))
        debug_log(_stream_get_next_key(0, self.query))
        debug_log(_stream_get_previous_key(0, self.query))


def update_spawn() -> float:
    return time()


WATCH = WatchMode(archetypes=[Probe], update_spawn=update_spawn)

LEVEL = {
    "bgmOffset": 0,
    "entities": [{"archetype": "Probe", "data": [{"name": "query", "value": 5.5}]}],
}
