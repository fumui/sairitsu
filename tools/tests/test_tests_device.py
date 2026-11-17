"""device-tests: what is pushed where, the sr_tests command line, reading its exit code. No device needed."""

from types import SimpleNamespace

from sairitsu_tools import tests_device
from sairitsu_tools.__main__ import main


class FakeDevice:
    def __init__(self, stdout: bytes):
        self.calls = []
        self.stdout = stdout

    def adb(self, *args, capture=False):
        self.calls.append(("adb",) + args)
        return SimpleNamespace(stdout=self.stdout if capture else b"")

    def shell(self, *args):
        self.calls.append(("shell",) + args)


def test_test_command_sets_the_environment_and_passes_doctest_arguments_through():
    command = tests_device.test_command(["-ts=vm", "-tc=a b*"])
    assert command == "cd /data/local/tmp/sr_tests && TMPDIR=/data/local/tmp/sr_tests/tmp ./sr_tests -ts=vm '-tc=a b*'"


def test_test_command_without_arguments_runs_everything():
    assert tests_device.test_command([]).endswith("./sr_tests")


def test_split_exit_reads_the_marker_line():
    assert tests_device.split_exit("out\n[doctest] Status: SUCCESS!\nSR_TESTS_EXIT=0\n") == (
        "out\n[doctest] Status: SUCCESS!", 0)
    assert tests_device.split_exit("boom\nSR_TESTS_EXIT=139\n") == ("boom", 139)
    assert tests_device.split_exit("no marker") == ("no marker", 1)


def test_run_on_device_pushes_with_sync_runs_and_returns_the_exit_code(tmp_path):
    binary = tmp_path / "sr_tests"
    binary.write_bytes(b"elf")
    fixtures = tmp_path / "fixtures"
    fixtures.mkdir()
    dev = FakeDevice(b"[doctest] Status: SUCCESS!\nSR_TESTS_EXIT=0\n")
    lines = []
    code = tests_device.run_on_device(dev, ["-ts=vm"], binary=binary, fixtures=fixtures, log=lines.append)
    assert code == 0
    pushes = [c for c in dev.calls if c[:2] == ("adb", "push")]
    assert pushes and all(c[2] == "--sync" for c in pushes)
    assert ("adb", "push", "--sync", str(binary), "/data/local/tmp/sr_tests/sr_tests") in pushes
    assert ("adb", "push", "--sync", str(fixtures), "/data/local/tmp/sr_tests") in pushes
    shell = [c for c in dev.calls if c[:2] == ("adb", "shell")]
    assert "./sr_tests -ts=vm" in shell[-1][2]
    assert any("[doctest] Status: SUCCESS!" in line for line in lines)


def test_run_on_device_returns_a_failing_exit_code(tmp_path):
    binary = tmp_path / "sr_tests"
    binary.write_bytes(b"elf")
    (tmp_path / "fixtures").mkdir()
    dev = FakeDevice(b"[doctest] Status: FAILURE!\nSR_TESTS_EXIT=1\n")
    code = tests_device.run_on_device(dev, [], binary=binary, fixtures=tmp_path / "fixtures", log=lambda m: None)
    assert code == 1


def test_main_passes_the_remaining_arguments_to_doctest(monkeypatch):
    seen = {}

    def fake_run(dev, args, **kw):
        seen["args"] = args
        return 3

    monkeypatch.setattr(tests_device, "build", lambda **kw: None)
    monkeypatch.setattr(tests_device, "run_on_device", fake_run)
    assert main(["device-tests", "--no-build", "--serial", "x", "--", "-ts=vm", "-s"]) == 3
    assert seen["args"] == ["-ts=vm", "-s"]
