from sairitsu_tools.device import Device


class FakeRun:
    def __init__(self):
        self.calls = []

    def __call__(self, argv, **kwargs):
        self.calls.append(list(argv))

        class R:
            returncode = 0
            stdout = b"PNGDATA"
            stderr = b""

        return R()


def test_start_ours_command():
    run = FakeRun()
    Device("dev:1", run=run).start_ours("watch", "example.com", "lvl", offline=True)
    argv = run.calls[0]
    assert argv[:5] == ["adb", "-s", "dev:1", "shell", "am"]
    assert "my.fumui.sairitsu/.gameplay.DebugGameplayEntry" in argv
    # A fresh process each time: a running GameplayActivity on top would otherwise get the intent as onNewIntent
    # and keep showing the previous level.
    assert "-S" in argv[argv.index("start"):argv.index("-n")]
    for flag, key, val in [("--es", "server", "example.com"), ("--es", "level", "lvl"),
                           ("--es", "mode", "watch"), ("--ez", "offline", "true")]:
        i = argv.index(key)
        assert argv[i - 1] == flag and argv[i + 1] == val


def test_screencap_force_stop(tmp_path):
    run = FakeRun()
    d = Device("dev:1", run=run)
    d.screencap(tmp_path / "s.png")
    d.force_stop("my.fumui.sairitsu")
    assert run.calls[0] == ["adb", "-s", "dev:1", "exec-out", "screencap", "-p"]
    assert (tmp_path / "s.png").read_bytes() == b"PNGDATA"
    assert run.calls[1][-3:] == ["am", "force-stop", "my.fumui.sairitsu"]


def test_serial_resolution(monkeypatch):
    monkeypatch.setenv("ANDROID_SERIAL", "env:1")
    assert Device(None, run=FakeRun()).serial == "env:1"
    assert Device("arg:1", run=FakeRun()).serial == "arg:1"
