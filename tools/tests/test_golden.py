import json
from pathlib import Path

from sairitsu_tools import golden
from sairitsu_tools.__main__ import main

COMMITTED = Path(__file__).resolve().parents[2] / "runtime" / "tests" / "fixtures" / "golden"


def read_all(folder: Path) -> dict[str, bytes]:
    return {p.name: p.read_bytes() for p in sorted(folder.glob("*.json"))}


def test_generator_is_deterministic(tmp_path):
    assert main(["golden", "--out", str(tmp_path / "a")]) == 0
    golden.write(tmp_path / "b")
    first = read_all(tmp_path / "a")
    assert first == read_all(tmp_path / "b")
    assert len(first) > 90
    assert read_all(tmp_path / "a" / "time") == read_all(tmp_path / "b" / "time")
    assert sum(len(v) for v in first.values()) < 2_000_000


def test_seed_changes_random_cases(tmp_path):
    golden.write(tmp_path / "a", seed=1)
    golden.write(tmp_path / "b", seed=2)
    assert read_all(tmp_path / "a")["Sin.json"] != read_all(tmp_path / "b")["Sin.json"]


def test_committed_fixtures_are_current(tmp_path):
    golden.write(tmp_path)
    assert read_all(tmp_path / "time") == read_all(COMMITTED / "time"), "re-run: python -m sairitsu_tools golden --out runtime/tests/fixtures/golden"
    assert read_all(tmp_path) == read_all(COMMITTED), "re-run: python -m sairitsu_tools golden --out runtime/tests/fixtures/golden"


def test_cases_follow_the_format(tmp_path):
    golden.write(tmp_path)
    doc = json.loads((tmp_path / "Add.json").read_text())
    assert doc["func"] == "Add"
    cases = doc["cases"]
    # 1e-45 is the smallest float32 denormal; 1e-45 + 1e-45 is exact in float32.
    assert {"args": [1e-45, 1e-45], "result": 3e-45, "tol_ulp": 0} in cases
    assert {"args": ["nan", 1.0], "result": "nan", "tol_ulp": 0} in cases
    assert {"args": ["inf", "-inf"], "result": "nan", "tol_ulp": 0} in cases
    floor = json.loads((tmp_path / "Floor.json").read_text())["cases"]
    assert {"args": [-0.0], "result": 0.0, "tol_ulp": 0, "any_zero": True} in floor
    sinh = json.loads((tmp_path / "Sinh.json").read_text())["cases"]
    assert {c["tol_ulp"] for c in sinh} == {4}  # SLEEF u35 bound against a rounded reference
    power = json.loads((tmp_path / "Power.json").read_text())["cases"]
    assert {c["tol_ulp"] for c in power} == {2}  # Sleef_powf_u10


def test_reference_failures_are_skipped(tmp_path):
    golden.write(tmp_path)
    log = json.loads((tmp_path / "Log.json").read_text())["cases"]
    assert not any(c["args"][0] in (0.0, -1.0) for c in log)  # math.log raises there
    arccos = json.loads((tmp_path / "Arccos.json").read_text())["cases"]
    assert not any(abs(c["args"][0]) > 1 for c in arccos if isinstance(c["args"][0], float))  # math.acos raises


def test_overrides_replace_the_python_reference(tmp_path):
    # These functions follow the runtime's semantics, not sonolus.py's interpret.py (golden.OVERRIDES).
    golden.write(tmp_path)

    def cases(func):
        return json.loads((tmp_path / f"{func}.json").read_text())["cases"]

    assert {"args": [-0.0], "result": 0.0, "tol_ulp": 0} in cases("Sign")
    assert {"args": ["nan"], "result": 0.0, "tol_ulp": 0} in cases("Sign")
    assert {"args": [-0.5], "result": -0.0, "tol_ulp": 0} in cases("Round")
    assert {"args": ["nan", 1.0], "result": 1.0, "tol_ulp": 0} in cases("Max")
    assert {"args": [-7.0, 3.0], "result": 2.0, "tol_ulp": 0} in cases("Mod")
    assert {"args": [7.0, -1.0], "result": 0.0, "tol_ulp": 0} in cases("Mod")  # +0, where Python gives -0
    rem = cases("Rem")
    assert {"args": [-7.0, 1.0], "result": 0.0, "tol_ulp": 0} in rem  # +0, where C fmod gives -0
    # InOutElastic uses period 2pi / 3 and offset 10.75 (sonolus.py: 2pi / 4.5, 11.125).
    elastic = {c["args"][0]: c["result"] for c in cases("EaseInOutElastic")}
    assert golden.f32(elastic[0.1]) == golden.f32(golden._in_out_elastic(golden.f32(0.1)))
    assert abs(elastic[0.1] - -0.00097656) < 1e-7
    assert any(c["args"][1] == 0 and c["result"] == "nan" for c in rem)  # by zero: NaN (interpret.py raises)


TIME_FUNCS = (
    "BeatToTime BeatToBPM BeatToStartingBeat BeatToStartingTime TimeToScaledTime TimeToStartingScaledTime "
    "TimeToStartingTime TimeToTimeScale"
).split()
EXPECTED_FUNCS = (
    "Abs Add And Arccos Arcsin Arctan Arctan2 Block Break Ceil Clamp Cos Cosh Degree Divide DoWhile Equal Execute "
    "Execute0 Floor Frac Greater GreaterOr If Judge JudgeSimple JumpLoop Lerp LerpClamped Less LessOr Log Max Min Mod "
    "Multiply Negate Not NotEqual Or Power Radian Rem Remap RemapClamped Round Sign Sin Sinh Subtract Switch "
    "SwitchInteger SwitchIntegerWithDefault SwitchWithDefault Tan Tanh Trunc Unlerp UnlerpClamped While"
).split() + golden.EASINGS + TIME_FUNCS
MIN_CASES = {"Block": 10, "SwitchInteger": 50, "SwitchIntegerWithDefault": 50, "Switch": 50, "SwitchWithDefault": 50, "Break": 50, "Execute0": 20, "While": 4, "DoWhile": 4, "JumpLoop": 100,
             "Judge": 100, "JudgeSimple": 100, "Remap": 100, "RemapClamped": 100}


def test_time_fixtures_follow_the_format(tmp_path):
    golden.write(tmp_path)
    assert sorted(p.stem for p in (tmp_path / "time").glob("*.json")) == sorted(TIME_FUNCS)
    assert not any(p.stem in TIME_FUNCS for p in tmp_path.glob("*.json"))  # the vm golden test reads only the top folder
    cases = json.loads((tmp_path / "time" / "BeatToTime.json").read_text())["cases"]
    assert {"bpm": [[0.0, 130.0]], "args": [13.0], "result": 6.0, "tol_ulp": 0} in cases
    # Without BPM changes BeatToTime is x * 60 / 0 (sonolus.py: a default 60 BPM table).
    assert {"args": [13.5], "result": "inf", "tol_ulp": 0} in cases
    # Before the first change: no section.
    assert {"bpm": [[0.0, 30.0]], "args": [-8.5], "result": "-inf", "tol_ulp": 0} in cases
    ts = json.loads((tmp_path / "time" / "TimeToScaledTime.json").read_text())["cases"]
    assert any("timescale" in c and c["timescale"][0][0] == 0 for c in ts)
    assert any(c["result"] == "nan" for c in cases)


def test_every_function_has_enough_cases(tmp_path):
    suite = golden.generate()
    assert sorted(suite.files) == sorted(EXPECTED_FUNCS)
    for func, cases in suite.files.items():
        assert len(cases) >= MIN_CASES.get(func, 100), func
    # Skips are domain errors only: at most a third of a function's candidate cases.
    for func, skipped in suite.skipped.items():
        assert skipped < len(suite.files[func]), func
