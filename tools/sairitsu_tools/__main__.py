import argparse
import sys
from pathlib import Path


def _device_tests(args) -> int:
    from . import tests_device
    from .device import Device
    if not args.no_build:
        tests_device.build()
    return tests_device.run_on_device(Device(args.serial), list(args.doctest))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="sairitsu_tools")
    sub = parser.add_subparsers(dest="group", required=True)
    gb = sub.add_parser("gen-blocks", help="generate runtime/src/vm/blocks_{watch,play}.inc from sonolus.py and the wiki")
    gb.add_argument("--wiki", type=Path, required=True, help="directory with the engine-spec wiki's block pages as text")
    gb.add_argument("--out", type=Path, default=None, help="output dir (default runtime/src/vm)")
    gb.add_argument("--check", action="store_true", help="fail if the committed tables are stale")
    gv = sub.add_parser("golden", help="write golden vectors (sonolus.py Interpreter results) for the C++ tests")
    gv.add_argument("--out", type=Path, required=True)
    gv.add_argument("--seed", type=int, default=None)
    dt = sub.add_parser("device-tests", help="run the C++ test suite (sr_tests) on an adb device from the adb shell")
    dt.add_argument("--serial", default=None)
    dt.add_argument("--no-build", action="store_true", help="use runtime/build/android-arm64/sr_tests as it is")
    dt.add_argument("doctest", nargs=argparse.REMAINDER, help="doctest arguments, after --, e.g. -- -ts=vm")
    be = sub.add_parser("build-engines", help="build the mini test engines into runtime/tests/fixtures/engines")
    be.add_argument("--check", action="store_true", help="fail if the committed builds are stale")
    args = parser.parse_args(argv)

    if args.group == "device-tests":
        if args.doctest[:1] == ["--"]:
            args.doctest = args.doctest[1:]
        return _device_tests(args)
    if args.group == "gen-blocks":
        from .gen_blocks import BlockError, write
        out = args.out or Path(__file__).resolve().parents[2] / "runtime" / "src" / "vm"
        try:
            stale = write(args.wiki, out, check=args.check)
        except BlockError as e:
            print(e, file=sys.stderr)
            return 1
        if stale:
            print("stale: " + ", ".join(stale), file=sys.stderr)
            return 1
        return 0
    if args.group == "golden":
        from . import golden
        paths = golden.write(args.out, log=print) if args.seed is None else golden.write(args.out, args.seed, log=print)
        print(f"{len(paths)} files, {sum(p.stat().st_size for p in paths)} bytes in {args.out}")
        return 0
    if args.group == "build-engines":
        from . import engines
        stale = engines.write(check=args.check)
        if stale and args.check:
            print("stale: " + ", ".join(stale), file=sys.stderr)
            return 1
        for name in stale:
            print(f"wrote {name}")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
