"""Mini engine: particle effect instances and scheduled effect clips.

Effect clips: "tick" (id 0), "hold" (id 1). Particle effects: "spark" (id 0), "absent" (id 1, which the C++ test's
particle data leaves out).
Each Note is active in [hit - 1, hit + 1). Its initialize schedules "tick" at `hit` with distance 0.25, schedules a
"hold" loop from `hit` and stops it at hit + 0.5, spawns a looped "spark" (duration 0.5) on the quad
(index, 0)-(index + 1, 1) and logs the particle id, then spawns "absent" and logs what that returns. Its
updateParallel moves the spark to (index, 1)-(index + 1, 2), spawns a one-shot spark (duration 0.25) and logs its
id. Its terminate destroys the looped spark.
"""

from sonolus.script.archetype import WatchArchetype, entity_memory, imported
from sonolus.script.debug import debug_log
from sonolus.script.effect import Effect, effect, effects
from sonolus.script.engine import WatchMode
from sonolus.script.particle import Particle, ParticleHandle, particle, particles
from sonolus.script.quad import Quad
from sonolus.script.runtime import time
from sonolus.script.vec import Vec2


@effects
class Effects:
    tick: Effect = effect("tick")
    hold: Effect = effect("hold")


@particles
class Particles:
    spark: Particle = particle("spark")
    absent: Particle = particle("absent")


def cell(x: float, y: float) -> Quad:
    return Quad(bl=Vec2(x, y), tl=Vec2(x, y + 1), tr=Vec2(x + 1, y + 1), br=Vec2(x + 1, y))


class Note(WatchArchetype):
    name = "Note"

    hit: float = imported()
    spark: ParticleHandle = entity_memory()

    def spawn_time(self) -> float:
        return self.hit - 1

    def despawn_time(self) -> float:
        return self.hit + 1

    def initialize(self):
        Effects.tick.schedule(self.hit, 0.25)
        loop = Effects.hold.schedule_loop(self.hit)
        loop.stop(self.hit + 0.5)
        self.spark = Particles.spark.spawn(cell(self.index, 0), 0.5, True)
        debug_log(self.spark.id)
        debug_log(Particles.absent.spawn(cell(self.index, 0), 0.5, False).id)

    def update_parallel(self):
        self.spark.move(cell(self.index, 1))
        debug_log(Particles.spark.spawn(cell(self.index, 1), 0.25, False).id)

    def terminate(self):
        self.spark.destroy()


def update_spawn() -> float:
    return time()


WATCH = WatchMode(archetypes=[Note], effects=Effects, particles=Particles, update_spawn=update_spawn)

LEVEL = {
    "bgmOffset": 0,
    "entities": [
        {"archetype": "Note", "data": [{"name": "hit", "value": 2}]},
        {"archetype": "Note", "data": [{"name": "hit", "value": 2.5}]},
    ],
}
