"""Golden vectors: pure runtime functions and control-flow programs evaluated by sonolus.py's Interpreter.

Every case is built as sonolus.backend.node.FunctionNode objects and run by `Interpreter.run` (doubles), and the
result is rounded to float32. The C++ test (runtime/tests/vm/golden_test.cpp) runs the same cases through
compile + run. Memory, time and host-effect functions are not covered: the cases use no blocks.

File format (one file per function): {"func": name, "cases": [case, ...]} with one case per line:
  {"args": [...], "result": r, "tol_ulp": n}     func applied to constant arguments
  {"tree": node, "result": r, "tol_ulp": n}      node = number | {"f": name, "a": [node, ...]}
plus optional keys on a case:
  "tol_abs": a      also accept |got - result| <= a (easings: sonolus.py evaluates them in double, the runtime in
                    float32 steps, and near a zero crossing the two differ by many ulps but under 1e-6 absolutely)
  "any_zero": true  result is zero and either sign of zero is accepted (easings: EaseInBack(1e-45) is -0 in double
                    because x**2 = 2e-90 does not underflow there, +0 in float32; Floor, Ceil, Trunc: interpret.py goes through
                    Python int, which loses the sign of zero that IEEE, JS and C# keep; the runtime keeps it)
Time functions (BeatToTime, BeatToBPM, BeatToStartingBeat, BeatToStartingTime, TimeToScaledTime,
TimeToStartingScaledTime, TimeToStartingTime, TimeToTimeScale) are written to the subfolder time/ and have the form
  {"bpm": [[beat, bpm], ...], "timescale": [[beat, scale], ...], "args": [x], "result": r, "tol_ulp": 0}
Their reference is `reference_time` below (the runtime's semantics), not
sonolus.py: they differ before the first change and without any change (sonolus.py extends the first section
and defaults to 60 BPM and scale 1). Either list may be omitted (no changes). Inputs are float32-exact (dyadic beats,
BPMs with a dyadic 60/bpm, dyadic scales); results are compared exactly, zero signs included.

Numbers are the shortest decimal that reads back as the same float32; NaN and infinities are the strings
"nan", "inf", "-inf" (JSON has no such numbers). The C++ side treats every NaN as equal to every NaN.

Cases for which sonolus.py raises (domain errors, division by zero, overflow, complex results, Rem/Mod by 0) are
left out: the reference has no value there.

Overrides: where the runtime's semantics differ from sonolus.py's Interpreter, the reference value comes from
OVERRIDES below instead, in float32 (or, for Rem/Mod, double) steps exactly as the runtime evaluates it.
"""

import ctypes
import itertools
import json
import math
import random
from pathlib import Path

import numpy as np

from sonolus.backend.interpret import Interpreter
from sonolus.backend.node import FunctionNode
from sonolus.backend.ops import Op

SEED = 14
F32_MAX = 3.4028234663852886e38
F32_TINY = 1.401298464324817e-45


def f32(x: float) -> float:
    """Round a Python float to float32 (overflow gives inf) and widen it back."""
    return ctypes.c_float(x).value


NAN = float("nan")
INF = float("inf")

# Values every function sees: NaN, +-0, +-Inf, +-1e-45, +-3.4e38, half-integers (Round), negatives (Mod, Rem).
SPECIAL = [f32(v) for v in (
    NAN, 0.0, -0.0, INF, -INF, F32_TINY, -F32_TINY, F32_MAX, -F32_MAX,
    1, -1, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5, 3, -3, 7, -7, 0.1, 100.25,
)]
# A shorter list for the cross products of three or more arguments.
SMALL = [f32(v) for v in (NAN, 0.0, -0.0, INF, -INF, 1, -1, 2.5, -2.5, 7)]
# Exact in float32 after sums, differences and products, so a double evaluation and a float32 one agree.
DYADIC = [k / 8 for k in range(-24, 25)]

# Tolerance in ulps: the rest need exact bits (0).
# The runtime's transcendentals are SLEEF routines, pinned bit for bit in
# runtime/tests/vm/edge_cases_test.cpp. Against this double reference they are within
# SLEEF's documented bounds: 3.5 ulp for the u35 functions (4 against a reference that is itself rounded), and
# 1 ulp for Sleef_powf_u10 (2).
TRANSCENDENTAL = {
    "Sin": 4, "Cos": 4, "Tan": 4, "Arcsin": 4, "Arccos": 4, "Arctan": 4, "Arctan2": 4,
    "Sinh": 4, "Cosh": 4, "Tanh": 4, "Log": 4, "Power": 2,
    # math.degrees is x * (180 / pi) with the constant in double; the runtime multiplies by a float32 constant.
    "Degree": 1, "Radian": 1,
}
EASINGS = [
    f"Ease{io}{kind}"
    for kind in ("Sine", "Quad", "Cubic", "Quart", "Quint", "Expo", "Circ", "Back", "Elastic")
    for io in ("In", "Out", "InOut", "OutIn")
]
for _name in EASINGS:
    TRANSCENDENTAL[_name] = 1
EASING_TOL_ABS = 1e-6
ZERO_SIGN_LOOSE = {"Floor", "Ceil", "Trunc"}


def _num(x):
    """JSON-safe number: non-finite values become strings, finite float32 the shortest round-tripping decimal."""
    x = float(x)
    if math.isnan(x):
        return "nan"
    if math.isinf(x):
        return "inf" if x > 0 else "-inf"
    text = str(np.float32(x))
    if f32(float(text)) != x or math.copysign(1.0, float(text)) != math.copysign(1.0, x):
        text = repr(x)  # a double that is exactly the float32, for the rare short form that double-rounds away
    return _Raw(x, text)


class _Raw(float):
    """The exact float32 value, serialising as its short decimal text (the file holds short float32 decimals)."""

    def __new__(cls, value, text):
        obj = super().__new__(cls, value)
        obj.text = text
        return obj


def _dump(value) -> str:
    if isinstance(value, _Raw):
        return value.text
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, dict):
        return "{" + ",".join(f"{json.dumps(k)}:{_dump(v)}" for k, v in value.items()) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(_dump(v) for v in value) + "]"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    raise TypeError(type(value))


# --- trees -------------------------------------------------------------------------------------------------

def N(name: str, *args):
    return {"f": name, "a": [_leaf(a) for a in args]}


def _leaf(a):
    return a if isinstance(a, dict) else _num(f32(a))


def to_node(tree):
    if isinstance(tree, dict):
        return FunctionNode(Op(tree["f"]), tuple(to_node(a) for a in tree["a"]))
    return float(tree)


# --- overrides ---------------------------------------------------------------------------------------------

_F = np.float32


def _nan(x) -> bool:
    return x != x


def _sign(x):
    # (x > 0) - (x < 0); -0 and NaN give +0 (Python/JS Math.sign keep them).
    return _F((1.0 if x > 0 else 0.0) - (1.0 if x < 0 else 0.0))


def _round(x):
    # Half to even, a zero result keeps x's sign (Python round() gives +0).
    if not math.isfinite(x):
        return _F(x)
    r = float(round(x))
    return _F(math.copysign(0.0, x) if r == 0 else r)


def _max(x, y):
    # IEEE maxNum / minNum; a NaN operand is ignored, max of the zeros is +0, min -0.
    x, y = _F(x), _F(y)
    if _nan(x):
        return y
    if _nan(y):
        return x
    if x == y:
        return y if math.copysign(1, x) < 0 else x
    return x if x > y else y


def _min(x, y):
    x, y = _F(x), _F(y)
    if _nan(x):
        return y
    if _nan(y):
        return x
    if x == y:
        return x if math.copysign(1, x) < 0 else y
    return x if x < y else y


def _rem2(x, y):
    # x - y * (int64)(x / y) in double (the quotient as
    # it is when |x / y| >= 2^63); a zero x over a non-zero y, and a finite x over an infinite y, give x.
    if (x == 0 and y != 0 and not math.isnan(y)) or (math.isinf(y) and math.isfinite(x)):
        return _F(x)
    with np.errstate(all="ignore"):
        dx, dy = np.float64(_F(x)), np.float64(_F(y))
        q = dx / dy
        t = np.float64(int(q)) if math.isfinite(q) and abs(q) < 2.0**63 else q
        return _F(dx - dy * t)


def _mod2(x, y):
    with np.errstate(all="ignore"):
        return _rem2(_F(_rem2(x, y) + _F(y)), y)


def _fold(fn):
    def f(*xs):
        if not xs:
            return _F(0)
        r = _F(xs[0])
        for v in xs[1:]:
            r = fn(r, v)
        return r
    return f


def _remap(a, b, c, d, v):
    # c + ((v - a) / (b - a)) * (d - c), float32 steps.
    a, b, c, d, v = (_F(z) for z in (a, b, c, d, v))
    with np.errstate(all="ignore"):
        return _F(c + _F(_F(_F(v - a) / _F(b - a)) * _F(d - c)))


def _remap_clamped(a, b, c, d, v):
    # Remap clamped to [min(c, d), max(c, d)].
    return _max(_min(c, d), _min(_max(c, d), _remap(a, b, c, d, v)))


_C1 = _F(1.70158)
_C3 = _F(_C1 + _F(1))


def _clamp01(x):
    x = _F(x)
    m = x if x < 1 else _F(1)
    return m if m > 0 else _F(0)


def _in_back(x):
    x = _clamp01(x)
    return _F(_F(_F(_F(_C3 * x) * x) * x) - _F(_F(_C1 * x) * x))


def _out_back(x):
    y = _F(_clamp01(x) - _F(1))
    return _F(_F(_F(1) + _F(_F(_F(_C3 * y) * y) * y)) + _F(_F(_C1 * y) * y))


# The Back halves fold the halving into the constants (c3 / 2, c1 / 2, 4 c3, 2 c1, as float32 bit patterns).
_C3X4, _C1X2 = np.uint32(0x412CE6B0).view(np.float32), np.uint32(0x4059CD60).view(np.float32)
_C3H, _C1H = np.uint32(0x3FACE6B0).view(np.float32), np.uint32(0x3F59CD60).view(np.float32)


def _in_out_back(x):
    x = _clamp01(x)
    if x < 0.5:
        return _F(_F(_F(_F(_C3X4 * x) * x) * x) - _F(_F(_C1X2 * x) * x))
    t = _F(_F(x + x) - _F(2))
    return _F(_F(_F(_F(_F(_C3H * t) * t) * t) + _F(1)) + _F(_F(_C1H * t) * t))


def _out_in_back(x):
    x = _clamp01(x)
    t = _F(_F(x + x) - _F(1))
    a = _F(_F(_F(_F(_C3H * t) * t) * t) + _F(0.5))
    b = _F(_F(_C1H * t) * t)
    return _F(a + b) if x < 0.5 else _F(a - b)


_PI = _F(math.pi)


def _in_out_elastic(x):
    # InOutElastic uses In/OutElastic's period and offset (2pi / 3, 10.75), not easings.net's 2pi / 4.5 and
    # 11.125. Float32 steps; sin and pow in double, rounded (the tolerance covers the SLEEF routines).
    x = _clamp01(x)
    if x == 0 or x == 1:
        return x
    a = _F(x * _F(20))
    angle = _F(_F(_F(_F(a - _F(10.75)) * _F(2)) * _PI) / _F(3))
    s = _F(math.sin(float(angle)))
    if x < 0.5:
        return _F(_F(s * _F(2.0 ** float(_F(a - _F(10))))) * _F(-0.5))
    return _F(_F(_F(_F(2.0 ** float(_F(_F(10) - a))) * s) * _F(0.5)) + _F(1))


OVERRIDES = {
    "Sign": _sign, "Round": _round, "Max": _max, "Min": _min,
    "Clamp": lambda x, a, b: _max(a, _min(b, x)),
    "Rem": _fold(_rem2), "Mod": _fold(_mod2),
    "Remap": _remap, "RemapClamped": _remap_clamped,
    "EaseInBack": _in_back, "EaseOutBack": _out_back, "EaseInOutBack": _in_out_back,
    "EaseOutInBack": _out_in_back, "EaseInOutElastic": _in_out_elastic,
}


TIME_FUNCS = {
    "BeatToTime": Op.BeatToTime, "BeatToBPM": Op.BeatToBPM, "BeatToStartingBeat": Op.BeatToStartingBeat,
    "BeatToStartingTime": Op.BeatToStartingTime, "TimeToScaledTime": Op.TimeToScaledTime,
    "TimeToStartingScaledTime": Op.TimeToStartingScaledTime, "TimeToStartingTime": Op.TimeToStartingTime,
    "TimeToTimeScale": Op.TimeToTimeScale,
}


def time_interpreter(bpm, timescale) -> Interpreter:
    """An Interpreter holding the given (beat, bpm) and (beat, scale) changes; the latter are converted to times."""
    it = Interpreter()
    if bpm:
        it.bpm_changes = [(float(b), float(v)) for b, v in bpm]
    if timescale:
        it.timescale_changes = [
            (it.run(FunctionNode(Op.BeatToTime, (float(b),))), float(v)) for b, v in timescale
        ]
    return it


def _section(starts, x):
    """Index of the last start not greater than x (all of them for NaN), or None before the first."""
    k = sum(1 for s in starts if not (s > x))
    return k - 1 if k else None


def _double_div(a: float, b: float) -> float:
    if b == 0:
        return math.nan if a == 0 or math.isnan(a) else math.copysign(math.inf, a)
    return a / b


def reference_time(func: str, x: float, bpm, timescale) -> float:
    """The runtime's time functions.

    Section times are doubles accumulated from the float32 beat difference; results are rounded to float32 once.
    Before the first change there is no section (BeatToTime is x * 60 / 0, BeatToBPM 0, TimeToScaledTime x,
    TimeToTimeScale 1); a level without BPM changes is all "before". Scaled time starts at the first timescale
    change's own time. sonolus.py instead extends the first section backwards and defaults to 60 BPM / scale 1."""
    changes = sorted(((f32(b), f32(v)) for b, v in bpm), key=lambda c: c[0])
    times, t = [], 0.0
    for i, (b, _) in enumerate(changes):
        if i:
            t = t + f32(b - changes[i - 1][0]) * 60.0 / changes[i - 1][1]
        times.append(t)

    def beat_to_time(beat: float) -> float:
        k = _section([b for b, _ in changes], beat)
        start, v, t0 = (0.0, 0.0, 0.0) if k is None else (changes[k][0], changes[k][1], times[k])
        return t0 + _double_div(f32(beat - start) * 60.0, v)

    scales = sorted(((f32(b), f32(v)) for b, v in timescale), key=lambda c: c[0])
    stimes = [beat_to_time(b) for b, _ in scales]
    scaled = []
    for i, at in enumerate(stimes):
        scaled.append(at if i == 0 else scaled[-1] + (at - stimes[i - 1]) * scales[i - 1][1])

    k = _section([b for b, _ in changes], x)
    j = _section(stimes, x)
    with np.errstate(all="ignore"):
        if func == "BeatToTime":
            return f32(beat_to_time(x))
        if func == "BeatToBPM":
            return 0.0 if k is None else changes[k][1]
        if func == "BeatToStartingBeat":
            return 0.0 if k is None else changes[k][0]
        if func == "BeatToStartingTime":
            return 0.0 if k is None else f32(times[k])
        if func == "TimeToScaledTime":
            return x if j is None else f32(scaled[j] + (x - stimes[j]) * scales[j][1])
        if func == "TimeToStartingScaledTime":
            return 0.0 if j is None else f32(scaled[j])
        if func == "TimeToStartingTime":
            return 0.0 if j is None else f32(stimes[j])
        if func == "TimeToTimeScale":
            return 1.0 if j is None else scales[j][1]
    raise ValueError(func)


def evaluate(tree):
    """Float32 result of the tree under sonolus.py's Interpreter (or OVERRIDES), or None when the reference raises."""
    if isinstance(tree, dict) and tree["f"] in OVERRIDES:
        if not all(not isinstance(a, dict) for a in tree["a"]):
            raise ValueError(f"override {tree['f']} only takes constant arguments")
        return float(OVERRIDES[tree["f"]](*(float(a) for a in tree["a"])))
    try:
        result = Interpreter().run(to_node(tree))
        if isinstance(result, complex):
            return None
        return f32(float(result))
    except (ValueError, ZeroDivisionError, OverflowError):
        return None


class Suite:
    def __init__(self):
        self.files: dict[str, list[str]] = {}
        self.skipped: dict[str, int] = {}

    def add(self, func: str, tree, tol: int | None = None, args=None):
        result = evaluate(tree)
        if result is None:
            self.skipped[func] = self.skipped.get(func, 0) + 1
            return
        if tol is None:
            tol = TRANSCENDENTAL.get(func, 0)
        case = {"args": [_num(f32(a)) for a in args]} if args is not None else {"tree": tree}
        case["result"] = _num(result)
        case["tol_ulp"] = tol
        if func in EASINGS:
            case["tol_abs"] = _Raw(EASING_TOL_ABS, repr(EASING_TOL_ABS))
        if (func in ZERO_SIGN_LOOSE or func in EASINGS) and result == 0:
            case["any_zero"] = True
        self.files.setdefault(func, []).append(_dump(case))

    def add_time(self, func: str, x, bpm=None, timescale=None):
        """One case of a time function at x under the given changes; the change lists are omitted when None."""
        result = reference_time(func, float(x), bpm or [], timescale or [])
        case = {}
        if bpm is not None:
            case["bpm"] = [[_num(b), _num(v)] for b, v in bpm]
        if timescale is not None:
            case["timescale"] = [[_num(b), _num(v)] for b, v in timescale]
        case["args"] = [_num(x)]
        case["result"] = _num(result)
        case["tol_ulp"] = 0
        self.files.setdefault(func, []).append(_dump(case))

    def call(self, func: str, *args, tol: int | None = None):
        self.add(func, N(func, *args), tol=tol, args=args)


# --- case generation ---------------------------------------------------------------------------------------

def _spread(rng: random.Random, n: int, lo: float, hi: float, wide: bool = True) -> list[float]:
    """n float32 values: uniform in [lo, hi], plus (if wide) a third spread over magnitudes."""
    out = [f32(rng.uniform(lo, hi)) for _ in range(n)]
    if wide:
        out += [f32(rng.choice((-1, 1)) * 10 ** rng.uniform(-8, 12)) for _ in range(n // 2)]
    return out


def _pairs(rng, extra_lo=-10.0, extra_hi=10.0, specials=SPECIAL, n_random=120):
    xs = list(itertools.product(specials, repeat=2))
    xs += [(f32(rng.uniform(extra_lo, extra_hi)), f32(rng.uniform(extra_lo, extra_hi))) for _ in range(n_random)]
    xs += [(f32(rng.randint(-20, 20)), f32(rng.randint(-6, 6))) for _ in range(n_random // 2)]
    return xs


UNARY_PURE = ["Abs", "Negate", "Not", "Sign", "Sin", "Cos", "Tan", "Arctan", "Sinh", "Cosh", "Tanh",
              "Degree", "Radian", "Floor", "Ceil", "Trunc", "Round", "Frac", "Log"]
BINARY_PURE = ["Add", "Subtract", "Multiply", "Divide", "Mod", "Rem", "Power", "Max", "Min", "Arctan2",
               "Equal", "NotEqual", "Less", "LessOr", "Greater", "GreaterOr"]


def generate(seed: int = SEED) -> Suite:
    rng = random.Random(seed)
    s = Suite()

    for func in UNARY_PURE:
        values = list(SPECIAL) + _spread(rng, 120, -10, 10)
        if func in ("Sinh", "Cosh", "Tanh"):
            values += _spread(rng, 80, -20, 20, wide=False)
        if func in ("Floor", "Ceil", "Trunc", "Round"):
            values += [k / 2 for k in range(-12, 13)] + [f32(2 ** 23 + k / 2) for k in range(-3, 4)]
        for v in values:
            s.call(func, v)
    for func in ("Arcsin", "Arccos"):
        for v in list(SPECIAL) + [f32(rng.uniform(-1.1, 1.1)) for _ in range(150)]:
            s.call(func, v)

    for func in BINARY_PURE:
        pairs = _pairs(rng)
        if func in ("Mod", "Rem"):
            # Negative operands, fractional divisors, and divisors larger than the dividend.
            pairs += [(f32(a), f32(b)) for a in (-7, -5.5, 5.5, 7, -0.0, 0.0, -1e-3, 1e30)
                      for b in (3, -3, 2.5, -2.5, 8, -8, 1, 1e-3)]
        if func == "Power":
            pairs += [(f32(rng.uniform(0, 20)), f32(rng.uniform(-5, 5))) for _ in range(100)]
            pairs += [(f32(rng.uniform(-8, 8)), f32(rng.randint(-5, 5))) for _ in range(60)]
        for a, b in pairs:
            s.call(func, a, b)

    # Variadic arithmetic: with float32-exact partial results, three or more operands agree with doubles.
    for func in ("Add", "Subtract", "Multiply"):
        s.call(func)
        for v in SMALL:
            s.call(func, v)
        for a, b, c in itertools.product((1, -2, 0.5, 4), repeat=3):
            s.call(func, a, b, c)
    for a, b, c in itertools.product((8, -4, 0.5, 2), (2, -1, 4), (2, 0.5, -1)):
        s.call("Divide", a, b, c)
    s.call("Divide")
    for a, b, c in itertools.product((29, -7, 100), (8, -5), (4, 3)):
        s.call("Mod", a, b, c)
        s.call("Rem", a, b, c)

    # Three-argument functions with exact bits.
    for a, b, c in itertools.product(SMALL, repeat=3):
        s.call("Clamp", a, b, c)
    for _ in range(100):
        s.call("Clamp", f32(rng.uniform(-5, 5)), f32(rng.uniform(-3, 0)), f32(rng.uniform(0, 3)))

    # Lerp / Remap / Unlerp: intermediates are float32-exact (multiples of 1/8, power-of-two spans), because the
    # wiki and the C++ evaluate each step in float32 and sonolus.py in double.
    spec = [NAN, 0.0, -0.0, INF, -INF]
    for _ in range(250):
        x, y, t = (f32(rng.choice(DYADIC)) for _ in range(3))
        s.call("Lerp", x, y, t)
        s.call("LerpClamped", x, y, f32(rng.choice(DYADIC + [3.0, -2.0])))
    for a, b, c in itertools.product(spec, repeat=3):
        s.call("Lerp", a, b, c)
        s.call("LerpClamped", a, b, c)
    for _ in range(250):
        lo = f32(rng.choice(DYADIC))
        span = 2.0 ** rng.randint(-2, 3)
        hi = f32(lo + span * rng.choice((1, 1, -1)))
        v = f32(rng.choice(DYADIC))
        s.call("Unlerp", lo, hi, v)
        s.call("UnlerpClamped", lo, hi, v)
        to_lo, to_hi = f32(rng.choice(DYADIC)), f32(rng.choice(DYADIC))
        s.call("Remap", lo, hi, to_lo, to_hi, v)
        s.call("RemapClamped", lo, hi, to_lo, to_hi, v)
    for a, b, c in itertools.product(spec, repeat=3):
        s.call("Unlerp", a, b, c)
        s.call("UnlerpClamped", a, b, c)
    for _ in range(60):
        s.call("Remap", *[f32(rng.choice(spec + [1.0, 2.0])) for _ in range(5)])

    # Judge / JudgeSimple (exact comparisons; diff = source - target is exact for these values).
    sixteenths = [k / 16 for k in range(-16, 17)]
    for _ in range(300):
        src, tgt = f32(rng.choice(sixteenths)), f32(rng.choice(sixteenths))
        w = sorted(abs(rng.choice(sixteenths)) for _ in range(3))
        s.call("JudgeSimple", src, tgt, *[f32(x) for x in w])
        wins = []
        for x in w:
            wins += [f32(-x * rng.choice((1, 0.5, 2))), f32(x * rng.choice((1, 0.5, 2)))]
        s.call("Judge", src, tgt, *wins)
    for a, b in itertools.product((NAN, 0.0, 0.5), repeat=2):
        s.call("JudgeSimple", a, b, 0.25, 0.5, 1.0)
        s.call("JudgeSimple", 0.1, 0.0, a, b, 1.0)

    # Easings: input clamped to [0, 1] inside, so cover outside it too.
    for name in EASINGS:
        for v in SPECIAL + [f32(k / 32) for k in range(0, 33)]:
            s.call(name, v)
        for _ in range(80):
            s.call(name, f32(rng.uniform(-0.1, 1.1)))

    _control_flow(s, rng)
    _time(s, rng)
    return s


# BPMs whose 60 / bpm is dyadic, so every step of the float32 evaluation is exact.
EXACT_BPMS = [15, 20, 30, 40, 60, 80, 96, 120, 160, 240, 480]
TIME_SPECIAL = [NAN, INF, -INF, 0.0, -0.0]


def _time(s: Suite, rng: random.Random):
    """BPM and timescale cases: random multi-section tables probed in, on, between and outside the sections."""
    for _ in range(14):
        n = rng.randint(1, 6)
        beats = sorted({0.0} | {float(rng.randint(1, 80)) / rng.choice((1, 2, 4)) for _ in range(n - 1)})
        bpm = [(b, float(rng.choice(EXACT_BPMS))) for b in beats]
        sbeats = sorted({0.0} | {float(rng.randint(1, 80)) / rng.choice((1, 2, 4)) for _ in range(rng.randint(0, 6))})
        scale = [(b, rng.choice((0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 0.0, 4.0))) for b in sbeats]
        probes_beat = [b for b, _ in bpm] + [b - 0.125 for b, _ in bpm] + [b + 0.25 for b, _ in bpm]
        probes_beat += [-1.0, -8.5, 100.0, 1000.0] + [k / 8 for k in rng.sample(range(-40, 700), 12)] + TIME_SPECIAL
        for x in probes_beat:
            for func in ("BeatToTime", "BeatToBPM", "BeatToStartingBeat", "BeatToStartingTime"):
                s.add_time(func, x, bpm)
        # Time probes: the section boundaries themselves (BeatToTime of a change beat), just around them, and far out.
        it = time_interpreter(bpm, scale)
        starts = [it.run(FunctionNode(Op.BeatToTime, (b,))) for b, _ in scale]
        probes_time = starts + [t - 0.125 for t in starts] + [t + 0.375 for t in starts]
        probes_time += [-3.0, -0.5, 500.0] + [k / 8 for k in rng.sample(range(-24, 400), 12)] + TIME_SPECIAL
        for x in probes_time:
            for func in ("TimeToScaledTime", "TimeToStartingScaledTime", "TimeToStartingTime", "TimeToTimeScale"):
                s.add_time(func, x, bpm, scale)
    # Defaults (no change lists): 60 BPM and scale 1.
    for x in TIME_SPECIAL + [-2.5, 0.0, 1.0, 13.5, 1000.0]:
        for func in ("BeatToTime", "BeatToBPM", "BeatToStartingBeat", "BeatToStartingTime"):
            s.add_time(func, x)
        for func in ("TimeToScaledTime", "TimeToStartingScaledTime", "TimeToStartingTime", "TimeToTimeScale"):
            s.add_time(func, x)
    # A single 130 BPM section: beat 13 is 6 s (the one non-dyadic BPM, chosen so 13 * 60 / 130 is exact).
    s.add_time("BeatToTime", 13.0, [(0.0, 130.0)])


def _control_flow(s: Suite, rng: random.Random):
    # If / And / Or / Execute / Execute0
    for t in SPECIAL:
        for a, b in ((1, 2), (NAN, -0.0), (INF, 5)):
            s.add("If", N("If", t, a, b))
    for t, u in itertools.product(SMALL, repeat=2):
        s.add("If", N("If", N("If", t, u, 0), N("Not", t), N("Add", u, 1)))
    for arity in range(4):
        for combo in itertools.product(SMALL[:7], repeat=arity):
            s.add("And", N("And", *combo))
            s.add("Or", N("Or", *combo))
            s.add("Execute", N("Execute", *combo))
            if arity <= 2:
                s.add("Execute0", N("Execute0", *combo))
    for t in SMALL:
        s.add("And", N("And", N("Or", t, 0), N("Equal", t, t)))
        s.add("Or", N("Or", N("And", t, 5), N("Negate", t)))
        s.add("Execute", N("Execute", N("Execute0", t), N("If", t, N("Execute", 1, t), 3)))

    # Switch family: keys compare with ==, so 0 matches -0 and NaN matches nothing.
    keys = [NAN, 0.0, -0.0, 1, 2.5, INF]
    for t, k1, k2 in itertools.product(keys, repeat=3):
        s.add("Switch", N("Switch", t, k1, 10, k2, 20))
        s.add("SwitchWithDefault", N("SwitchWithDefault", t, k1, 10, k2, 20, 30))
    for t in keys:
        s.add("Switch", N("Switch", t))
        s.add("Switch", N("Switch", t, t, 5))
        s.add("SwitchWithDefault", N("SwitchWithDefault", t, 9))
    idx = [-1, 0, -0.0, 0.5, 1, 2, 3, 4, NAN, INF, -INF, 1e-45, 2.0000002, 1e10]
    for t in idx:
        for n in range(0, 4):
            branches = [10, 20, 30][:n]
            s.add("SwitchInteger", N("SwitchInteger", t, *branches))
            s.add("SwitchIntegerWithDefault", N("SwitchIntegerWithDefault", t, *branches, 99))

    # While / DoWhile without state: a zero test ends the loop at once (DoWhile runs the body first).
    for t in (0.0, -0.0):
        s.add("While", N("While", t, 7))
        s.add("While", N("Execute", N("While", t, N("Add", 1, 1)), 4))
        s.add("DoWhile", N("DoWhile", 5, t))
        s.add("DoWhile", N("Execute", N("DoWhile", N("Add", 1, 1), t), 4))

    # JumpLoop: forward jumps only (a backward jump with no state would loop forever); the last argument is the
    # value of the loop. A next index that is not an integer in range ends the loop with 0.
    for a0, a1, a2 in itertools.product((1, 2, 3, 0.5, NAN, -1, 4, INF), (2, 3, -1, 2.5, NAN, 5), (3, 9, NAN, 3.5, -1)):
        s.add("JumpLoop", N("JumpLoop", a0, a1, a2, 77))
    s.add("JumpLoop", N("JumpLoop", 5))
    s.add("JumpLoop", N("JumpLoop", 1, 8))
    s.add("JumpLoop", N("JumpLoop", 1, 2, 3))

    # Block / Break: Break(n, v) leaves the n innermost blocks with v.
    for v in SPECIAL:
        s.add("Block", N("Block", N("Break", 1, v)))
        s.add("Block", N("Block", N("Execute", N("Break", 1, v), 5)))
        s.add("Break", N("Block", N("Block", N("Break", 2, v))))
        s.add("Break", N("Block", N("Execute", N("Block", N("Break", 1, v)), 9)))
        s.add("Break", N("Block", N("Execute", N("Block", N("Break", 2, v)), 9)))
        s.add("Block", N("Block", N("If", v, N("Break", 1, 2), 3)))
        s.add("Block", N("Block", v))
    s.add("Break", N("Block", N("Block", N("Block", N("Break", 3, 1.5)))))


def write(out: Path, seed: int = SEED, log=None) -> list[Path]:
    """Write the fixtures into `out` (created; stale *.json removed). Returns the files written."""
    suite = generate(seed)
    if log is not None:
        for func, n in sorted(suite.skipped.items()):
            log(f"{func}: {n} cases skipped (sonolus.py raises)")
    (out / "time").mkdir(parents=True, exist_ok=True)
    for old in [*out.glob("*.json"), *(out / "time").glob("*.json")]:
        old.unlink()
    paths = []
    for func in sorted(suite.files):
        path = (out / "time" if func in TIME_FUNCS else out) / f"{func}.json"
        body = ",\n".join(suite.files[func])
        path.write_bytes(f'{{"func":"{func}","cases":[\n{body}\n]}}\n'.encode("utf-8"))
        paths.append(path)
    return paths
