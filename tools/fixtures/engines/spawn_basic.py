"""Mini engine: imports, refs, spawn and despawn times, and a preprocess that lays out the UI.

Note spawns at its beat and despawns `duration` beats later (at 120 BPM). `duration` has an import default; the
level gives `#JUDGMENT` a value the runtime must ignore (no replay), and `prev` refers to another Note.
Layout's preprocess writes the menu layout into Runtime UI from Runtime Environment.
Note's initialize logs 1000 + index and its terminate 2000 + index with DebugLog (the update cycle tests).
"""

from typing import Any

from sonolus.script.archetype import EntityRef, WatchArchetype, imported
from sonolus.script.debug import debug_log
from sonolus.script.engine import WatchMode
from sonolus.script.runtime import aspect_ratio, runtime_ui, time
from sonolus.script.timing import beat_to_time
from sonolus.script.vec import Vec2


class BpmChange(WatchArchetype):
    name = "#BPM_CHANGE"

    beat: float = imported(name="#BEAT")
    bpm: float = imported(name="#BPM")


class Note(WatchArchetype):
    name = "Note"

    beat: float = imported(name="#BEAT")
    duration: float = imported(default=2.5)
    judgment: float = imported(name="#JUDGMENT", default=3)
    accuracy: float = imported(name="#ACCURACY")
    prev: EntityRef[Any] = imported()

    def spawn_time(self) -> float:
        return beat_to_time(self.beat)

    def despawn_time(self) -> float:
        return beat_to_time(self.beat + self.duration)

    def initialize(self):
        debug_log(1000 + self.index)

    def terminate(self):
        debug_log(2000 + self.index)


class Layout(WatchArchetype):
    name = "Layout"

    def preprocess(self):
        runtime_ui().menu.update(
            anchor=Vec2(aspect_ratio(), 0.5),
            pivot=Vec2(1, 1),
            dimensions=Vec2(0.25, 0.125),
            alpha=0.75,
            background=True,
        )


def update_spawn() -> float:
    return time()


WATCH = WatchMode(archetypes=[BpmChange, Note, Layout], update_spawn=update_spawn)

LEVEL = {
    "bgmOffset": 0,
    "entities": [
        {"archetype": "#BPM_CHANGE", "data": [{"name": "#BEAT", "value": 0}, {"name": "#BPM", "value": 120}]},
        {"name": "a", "archetype": "Note", "data": [{"name": "#BEAT", "value": 4}]},
        {
            "name": "b",
            "archetype": "Note",
            "data": [
                {"name": "#BEAT", "value": 6},
                {"name": "duration", "value": 1},
                {"name": "#JUDGMENT", "value": 1},
                {"name": "#ACCURACY", "value": 0.5},
                {"name": "prev", "ref": "a"},
                {"name": "unknown", "value": 9},
            ],
        },
        {"archetype": "Layout", "data": []},
    ],
}
