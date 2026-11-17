"""Run sr_tests on an adb device: build it for arm64, push it with its fixtures, run it from the adb shell, return its
exit code.

The device copy lives in /data/local/tmp/sr_tests (adb shell only; no app involved):
  sr_tests                                     the executable (runtime/build/android-arm64/sr_tests)
  fixtures/                                    runtime/tests/fixtures (SR_FIXTURE_DIR, SR_TEST_FIXTURES on the device)
  tmp/                                         $TMPDIR
"""

import shlex
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RUNTIME = REPO / "runtime"
DEVICE_TESTS = RUNTIME / "build" / "android-arm64" / "sr_tests"
FIXTURES = RUNTIME / "tests" / "fixtures"
REMOTE = "/data/local/tmp/sr_tests"
EXIT_MARK = "SR_TESTS_EXIT="


def test_command(args: list[str]) -> str:
    """The device shell command: sr_tests with the doctest arguments as given."""
    return " ".join(["cd", REMOTE, "&&", f"TMPDIR={REMOTE}/tmp", "./sr_tests"] + [shlex.quote(a) for a in args])


def split_exit(output: str) -> tuple[str, int]:
    """(output without the marker line, the exit code it carries); 1 when the marker is missing."""
    lines = output.splitlines()
    if lines and lines[-1].strip().startswith(EXIT_MARK):
        return "\n".join(lines[:-1]), int(lines[-1].strip()[len(EXIT_MARK):])
    return output, 1


def _run_logged(run, argv: list[str], log, capture: bool):
    try:
        return run(argv, cwd=RUNTIME, check=True, capture_output=capture, text=True)
    except subprocess.CalledProcessError as e:
        log(f"failed: {' '.join(argv)}")
        for text in (e.stdout, e.stderr):
            if text:
                log(text)
        raise


def build(run=subprocess.run, log=print) -> Path:
    """Configure and build sr_tests for the device (CMake preset android-arm64)."""
    _run_logged(run, ["cmake", "--preset", "android-arm64"], log, capture=True)
    _run_logged(run, ["cmake", "--build", "--preset", "android-arm64-tests"], log, capture=False)
    return DEVICE_TESTS


def run_on_device(dev, args: list[str], *, binary: Path = DEVICE_TESTS, fixtures: Path = FIXTURES, log=print) -> int:
    """Push sr_tests and its fixtures, run it with the doctest `args`, print its output and return its exit code."""
    dev.shell("mkdir", "-p", f"{REMOTE}/tmp")
    dev.adb("push", "--sync", str(binary), f"{REMOTE}/sr_tests")
    dev.shell("chmod", "755", f"{REMOTE}/sr_tests")
    dev.adb("push", "--sync", str(fixtures), REMOTE)  # a directory lands as REMOTE/fixtures
    command = test_command(args)
    log(f"device: {command}")
    r = dev.adb("shell", f"{command} 2>&1; echo {EXIT_MARK}$?", capture=True)
    text, code = split_exit(r.stdout.decode("utf-8", "replace"))
    log(text)
    return code
