# MADR: Improved Numeric Semantics in sjsonnet

## Status

Accepted

## Context

sjsonnet previously represented all non-integer numbers as IEEE-754 doubles. This matched many JSON implementations but caused correctness and determinism issues:

* Loss of precision for large or high-precision numbers
* Inconsistent ordering and equality for numerically equivalent values
* Rounding surprises during evaluation
* Difficulty implementing spec-accurate numeric semantics

At the same time, using arbitrary-precision numbers everywhere can impose performance costs, and some consumers prefer float semantics for speed.

Scala 3.3.7 uses `DECIMAL128` as the default `BigDecimal` math context, which provides a well-defined precision/rounding model suitable as a default numeric representation.

## Decision

We introduce a richer numeric model with three explicit numeric representations:

* **Int64** — exact 64-bit integers
* **Float64** — IEEE double (fast, inexact)
* **Dec128** — `BigDecimal` with `DECIMAL128` precision (exact within 34 digits)

Default behavior:

* All floating-point literals parse as **Dec128**
* Arithmetic between numeric values promotes to the most precise representation needed to preserve correctness
* Numeric comparisons are defined in a cross-type safe way

Opt-out — `-Dsjsonnet.floatAsBigDecimal=false`:

* Non-integer **literals** parse as `Float64` instead of `Dec128`, trading exactness for the lower
  allocation cost of a boxed double. Only literals a double can hold within 17 significant digits
  and an exponent in `[-325, 325]` are admitted (`NumberMath.allowFloat64LiteralWithIndexes`);
  anything else still parses as `Dec128`, so the flag can lose precision but cannot lose magnitude.
* This is a **parsing/representation switch only — it does not gate arithmetic promotion.** A
  `Float64` still promotes to `Dec128` on its first arithmetic operation, so `0.1 + 0.2` is `0.3`
  either way. Promotion is deliberately unconditional: `BigDecimal.decimal(d)` reinterprets a
  double as its shortest round-tripping decimal, so skipping it would change the *answer*, not
  merely the mantissa width.
* What the flag therefore changes is how a literal is spelled back out, since `Float64` renders
  through the double path: `0.12345678901234567` becomes `0.12345678901234566`, and whole values
  expand in full rather than using `Dec128`'s 1e21 scientific-notation window (`1e21` prints as
  `1000000000000000000000`). That is upstream's binary64 behaviour, which is the point of the flag.
* There is **no float-only arithmetic mode.** If one is ever wanted it needs its own flag; every
  promotion decision routes through the single predicate `NumberMath.promoteFloat64Arithmetic`
  (hardcoded `true`) precisely so that adding one is a one-line change rather than five scattered
  edits across the evaluator's fast paths.

Library API change:

* Visitors that handle numeric materialization must be prepared to receive decimal values through `visitFloat64StringParts`, which is now used for decimal materialization.
* **`Interpreter.interpret` narrows.** It is the convenience overload for `interpret0(txt, path, ujson.Value)`, and `ujson.Value`'s AST stores every number as a `Double` — so an exact `Int64` past 2^53 comes back rounded (or as a `Str`), and a `Dec128` outside binary64 range comes back `Infinity`. Nothing in this design can fix that without replacing ujson.

  **Consumers who need the exactness must call `interpret0` with their own visitor** and implement `visitFloat64StringParts` to build a decimal-aware value. That is the intended integration path and the one xtrasonnet uses. The renderers — and therefore the CLI — are exact; it is only this one convenience overload that is not.

  This is also why ten golden tests are skip-listed in `FileTests.scala`: that harness compares `interpret`'s `ujson.Value`, so it structurally cannot express the values under test. See `UPSTREAM_SYNC.md` → "Known divergences from upstream".

### Scope: the language core is exact; the standard library is not

This rework applies to the **language core** — literal parsing, the infix operators (`+ - * / %`, comparisons, bitwise/shift, indexing), and the materialization/rendering pipeline. Those are exact.

**`std.*` functions are deliberately left on IEEE-754 double semantics, matching upstream sjsonnet**, with one floor:

> A `std` function may **round** its result or **fail** on a value outside double range.
> It may not **change, merge, or drop the caller's own values**.

Concretely, that floor requires exactness in a small enumerable set, and everything else in `std` keeps narrowing through `asDouble` exactly as upstream does:

| Must stay exact | Because narrowing would… |
|---|---|
| `std.sort` (and the `set*` family's comparisons) | rebuild the array from doubles, so sorting **returns different numbers than you passed in** |
| `std.setUnion` / `setInter` / `setDiff` / `uniq` | make two distinct values compare equal, **silently dropping** an element |
| `std.primitiveEquals` | report two distinct numbers as **equal** |
| `std.parseInt` / `parseOctal` / `parseHex` | round the integer it was asked to parse |
| `std.format`'s `%s` (and `std.toString`) | put a **different number** in the output string, and disagree with each other |
| `std.format`'s `%d` `%i` `%u` `%o` `%x` `%X` | same, for the *integer* conversions: `'%x' % 9223372036854775807` spelled 2^63 |

Number-to-string conversion has more spellings than those two, and **all of them must agree**:
`std.toString`, `%s`, `%(key)s`, and `+` concatenation with a string. They share
`RenderUtils.renderNum` for exactly that reason. This is the easiest part of the rework to
half-finish — each has its own fast path, and three of the four were found narrowing
independently, at which point `'' + n` and `std.toString(n)` disagreed about the same value.

Everything else — `std.sum`, `std.avg`, `std.mod`, `std.modulo`, `std.clamp`, `std.min`/`max`, `std.floor`/`ceil`/`round`/`abs`, and all trigonometric/logarithmic functions — narrows to `Double`. So `std.sum([0.1, 0.2])` is `0.30000000000000004` while `0.1 + 0.2` is `0.3`, and `std.floor(1e400)` raises `Overflow`. **This is intended, not an oversight.**

`std.format` splits along the line C and Python already drew, not along `%s` vs. everything else:

* **Integer conversions — `%d` `%i` `%u` `%o` `%x` `%X` — are exact.** These format an integer in some base; rounding is not their defined behaviour, and Python (whose `%`-formatting `std.format` follows) is arbitrary precision for them. Narrowing them put a *different number* in the output — `'%x' % 9223372036854775807` spelled 2^63 — which is a floor violation, not rounding. Note `'%d' % 1e30` is therefore exactly 10^30 here, where Python prints `1000000000000000019884624838656` because its `1e30` really is a float.
* **Floating-point conversions — `%e` `%E` `%f` `%F` `%g` `%G` — still narrow**, because rounding to a fixed precision *is* what they are for. `'%.0f' % 1e30` accordingly still shows the binary64 artifact that `'%d'` no longer does.
* `%c` narrows too: a codepoint has to fit an `Int` regardless.

Rationale:

1. **A consistent line is impossible anyway.** `std.sqrt`, `std.pow`, `std.log` and the trigonometric functions return irrational values; they can never be exact. Upgrading only the functions that *could* be exact produces a stdlib where some functions are and some aren't, with no rule a user can predict.
2. **Correctness belongs in `xtr`.** This fork exists to support xtrasonnet's `xtr.*` function library. Exact aggregation and decimal-aware helpers belong there, where they are owned outright, rather than as divergences inside `std`.
3. **Upstream sync cost.** `std` is upstream's most actively maintained surface. Every `std` file we diverge in is a permanent merge-conflict surface on every sync, paid forever, for behaviour a user can get from `xtr`.

**For future sessions:** do not "fix" a `std` function's precision because it looks inconsistent. The inconsistency is the decision. Change a `std` function only if it violates the floor above — i.e. only if it returns, merges, or drops a value the caller did not supply.

## Consequences

### Positive

* Deterministic numeric semantics
* Spec-aligned ordering and equality
* No silent precision loss by default
* Explicit control over performance vs precision tradeoff
* Clear numeric promotion rules

### Negative

* Slightly higher runtime cost for decimal arithmetic
* Visitor implementations must handle decimal dispatch
* More numeric types in internal representation increases implementation complexity
* `std` functions disagree with the operators they resemble — `std.sum([0.1, 0.2])` is not `0.1 + 0.2`, and `std.mod(a, b)` is not `a % b`. Accepted per the scope decision above.
* Values outside binary64 range are now constructible, so any `std` function that narrows can raise `Overflow` on input the language accepts (`std.floor(1e400)`). Upstream never hits this because `1e309` is a *parse* error there; here it is a valid `Dec128`. The error is well-defined, not silent corruption.
* Precision beyond 17 significant digits is silently dropped when a value passes through a narrowing `std` function.
* **Known limitation — negative zero on Scala Native 2.13.** A `-0` produced by *arithmetic*
  (`-0`, `0 * -1`, `-6 % 3`, `{a: -0}`) renders as `0` there. A `-0` that arrives as literal text
  (`std.parseJson("-0")`, JSON import) is correct, and every other platform is correct: JVM
  2.12/2.13/3.3, Scala.js, WASM, and Scala Native **3.3** all preserve it.

  Cause: neither `Long` nor `BigDecimal` has a signed zero, so `NumberMath.signZero` has to
  manufacture one from a `-0.0` literal when an exact zero result should be negative. That literal
  does not survive the Scala Native 2.13 toolchain, while the identical literal in
  `Val.Num.apply` does — so it is a backend/version quirk rather than a design fault, and it is
  the intersection of Native *and* 2.13, not either alone. Before this rework every number was a
  `Double` and `-0` never had to be reconstructed, which is why the problem is new.

  **Not fixed, deliberately.** This fork exists to serve xtrasonnet, which is JVM-only, so a
  signed-zero edge case on one non-JVM backend does not justify carrying a workaround in the
  numeric core — every extra divergence is paid on every upstream sync. A one-line fix is known
  if it ever matters: compute the value (`Math.copySign(0.0, -1.0)`) instead of writing the
  literal. Note the affected tests do fail on that target — see `NUMBERS_REWORK_PLAN.md`.

### Neutral

* `Float64` literal representation remains available via the opt-out above; float-only
  *arithmetic* does not — see that section for why, and for what the flag actually changes
* Numeric behavior is now more predictable but slightly less “JavaScript-like”

## Alternatives Considered

1. **Keep doubles everywhere**

   * Rejected: precision and correctness issues.
2. **Always use BigDecimal**

   * Rejected: unnecessary cost for integer-only or float-friendly workloads.
3. **Configurable global numeric mode**

   * Rejected: makes libraries harder to reason about and compose.

## Notes

This design intentionally favors correctness by default while preserving an escape hatch for performance-critical workloads.
