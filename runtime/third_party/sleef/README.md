# sleef

No SLEEF source is vendored here. This folder holds the licence for code in `runtime/src/vm` that derives from
SLEEF (https://sleef.org, Boost Software License 1.0, `LICENSE.txt`).

The runtime's transcendentals are SLEEF routines written out operation by operation without fused multiply-add,
so host and device give the same bits. They carry SLEEF's algorithms, polynomial coefficients and reduction
constants.

Derived files and the SLEEF routines behind them:

- `runtime/src/vm/fmath_sleef.cpp`
  - `sin`: `xsinf` (u35), with `rempif` for large arguments
  - `cos`: `xcosf` (u35)
  - `tan`: `xtanf` (u35)
  - `asin`, `acos`: `xasinf`, `xacosf` (u35)
  - `atan`, `atan2`: `xatanf`, `atan2kf` / `xatan2f` (u35)
  - `sinh`, `tanh`: `expm1fk` (the `sinh` wrapper as `xsinhf_u35`; a custom `tanh` wrapper)
  - `cosh`: `xexpf`, `xcoshf_u35`
  - `log`: `xlogf` (u35)
  - `pow`: `xpowf` (u10) with `logkf`, `expkf`, `vldexp`
  - the double-float helpers (`df*`) and `rempif` (with a custom quarter-turn step)
- `runtime/src/vm/fmath_rempi.inc`: the reduction table, the same values as SLEEF's `Sleef_rempitabsp`.

The copyright line in `LICENSE.txt` is the one in SLEEF's source headers (SLEEF 3.x). No copy of SLEEF's own
source was available offline when this was written, so it was not re-checked against a release.
