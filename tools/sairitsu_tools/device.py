"""adb control of an Android test device."""

import os
import subprocess
import tomllib
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent.parent
OURS_ENTRY = "my.fumui.sairitsu/.gameplay.DebugGameplayEntry"


def resolve_serial(serial: str | None) -> str | None:
    """The adb serial: the argument, else $ANDROID_SERIAL, else `serial` in tools/device.local.toml, else adb's
    default device."""
    if serial:
        return serial
    if os.environ.get("ANDROID_SERIAL"):
        return os.environ["ANDROID_SERIAL"]
    local = TOOLS_DIR / "device.local.toml"
    if local.exists():
        with open(local, "rb") as f:
            return tomllib.load(f).get("serial")
    return None


class Device:
    def __init__(self, serial: str | None = None, run=subprocess.run):
        self.serial = resolve_serial(serial)
        self._run = run

    def adb(self, *args: str, capture: bool = False):
        argv = ["adb"] + (["-s", self.serial] if self.serial else []) + list(args)
        r = self._run(argv, check=True, capture_output=capture) if capture else self._run(argv, check=True)
        return r

    def shell(self, *args: str):
        return self.adb("shell", *args)

    def screencap(self, out: Path) -> Path:
        r = self.adb("exec-out", "screencap", "-p", capture=True)
        Path(out).write_bytes(r.stdout)
        return Path(out)

    def start_ours(self, mode: str, server: str, level: str, **extras):
        # -S: force-stop the app first, so every start is a fresh process (an instance on top would otherwise get
        # the intent as onNewIntent and keep its level).
        args = ["am", "start", "-S", "-n", OURS_ENTRY,
                "--es", "server", server, "--es", "level", level, "--es", "mode", mode]
        for key, value in extras.items():
            if isinstance(value, bool):
                args += ["--ez", key, "true" if value else "false"]
            elif isinstance(value, int):
                args += ["--ei", key, str(value)]
            else:
                args += ["--es", key, str(value)]
        self.shell(*args)

    def force_stop(self, package: str):
        self.shell("am", "force-stop", package)
