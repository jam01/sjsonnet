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
* **One `Float64` operand makes an arithmetic operation IEEE-754**, on operands reduced to `Double`.
  `Float64` is the inexact tier by construction, so inexactness is a property of the value and
  propagates rather than being re-exactified by the next literal it meets. `Int64` and `Dec128`
  arithmetic is untouched, so this reaches only `std` results and `-0` unless the opt-out is set.

Opt-out — `-Dsjsonnet.floatAsBigDecimal=false`:

* Non-integer **literals** parse as `Float64` instead of `Dec128`, trading exactness for the lower
  allocation cost of a boxed double. Only literals a double can hold within 17 significant digits
  and an exponent in `[-325, 325]` are admitted (`NumberMath.allowFloat64LiteralWithIndexes`);
  anything else still parses as `Dec128`, so the flag can lose precision but cannot lose magnitude.
* The flag is a **parsing switch, but its effect is not confined to parsing**, because `Float64`
  arithmetic is IEEE-754 (see below). Making literals `Float64` therefore makes ordinary literal
  arithmetic binary64 too: `0.1 + 0.2` is `0.3` by default and `0.30000000000000004` under the
  opt-out, and `0.3 % 0.1` is `0` and `0.09999999999999998`. That is upstream's arithmetic, which
  is the point of the flag — opting out of `BigDecimal` opts out of its answers.
* It also changes how a literal is spelled back out, since `Float64` renders through the double
  path: `0.12345678901234567` becomes `0.12345678901234566`, and whole values expand in full rather
  than using `Dec128`'s 1e21 scientific-notation window (`1e21` prints as `1000000000000000000000`).

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

* `Float64` literal representation remains available via the opt-out above, and because `Float64`
  arithmetic is IEEE-754, that opt-out now delivers upstream's arithmetic as well as its spellings
* A `Dec128` outside binary64's range collapses to an infinity when it meets a `Float64`, and
  `Val.Float64` rejects those, so `1e400 * std.sqrt(2)` raises `Overflow` where `1e400 * 2` stays
  exact — the same trade upstream makes for every number it holds
* Numeric behavior is now more predictable but slightly less “JavaScript-like”

## Alternatives Considered

1. **Keep doubles everywhere**

   * Rejected: precision and correctness issues.
2. **Always use BigDecimal**

   * Rejected: unnecessary cost for integer-only or float-friendly workloads.
3. **Configurable global numeric mode**

   * Rejected: makes libraries harder to reason about and compose.

### Tried during implementation and reverted

Recorded so they are not attempted again. Each looked reasonable and is wrong.

1. **Guard `Dec128` to finite-`Double` magnitude**, to keep `error.overflow*` / `div4` / `inf_*`
   passing unchanged. This makes `Dec128` "a double with extra digits". Its exponent range
   (~1e±6144) being far wider than binary64's is the entire point; overflow at binary64 boundaries
   is precisely the behaviour the rework exists to remove.
2. **Raw `Double` only when *both* operands are `Float64`.** The narrow version of the float rule
   now in force, and it is self-inconsistent: it splits `x + x` from `x * 2` for a `Float64` `x`,
   because the latter meets an `Int64` and re-enters the exact core. `std.sqrt(2) + std.sqrt(2)`
   gave `2.8284271247461903` while `std.sqrt(2) * 2` gave `2.8284271247461902` — the same quantity,
   two answers, where upstream and full promotion each give one. Contagion was adopted instead.
   Note what either version costs, since promotion was not arbitrary: `BigDecimal.decimal(d)`
   reinterprets a double as its shortest round-tripping decimal, so raw and promoted disagree on
   ordinary values, and `%` is the trap — IEEE `fmod` is binary-exact and so looks safe, yet
   `0.3 % 0.1` is `0.09999999999999998` raw and `0` promoted. Comparison and bitwise/shift were
   always raw regardless.
3. **Gate the comprehension accelerator on element type** (statically, or by checking the first
   element at runtime). Neither helps, because the mismatch is not the element type:
   `[x / 3 for x in std.range(...)]` differs between raw-`Double` and `NumberMath` whatever the
   elements are. The arithmetic pipeline was removed instead.
4. **A compatibility `Val.Num.unapply` returning `(pos, Double)`**, to keep the tree compiling
   during the split. It would have silently routed `Int64 op Int64` through `Double` at ~32 sites
   including the constant folder, where a wrong-but-plausible fold looks like success. The compile
   errors were the worklist.
5. **`Val.Float64` rejecting NaN at construction.** Upstream rejects only infinity there and NaN
   lazily in `asDouble`; moving the check earlier changes both the message and its position.
6. **Assuming mixed-representation comparison dominated `cpp_suite/bench.06`.** A `Long` fast path
   for `Int64`↔whole-`Float64` comparison was added and measured: no effect. The fast path was kept
   on its own merits (allocation-free, provably equivalent). The benchmark's actual cost was
   `BigDecimal./` — nothing to do with comparison, sorting, or representation. See
   `UPSTREAM_SYNC.md` → "Measured performance".

## Notes

This design intentionally favors correctness by default while preserving an escape hatch for performance-critical workloads.

Two implementation invariants are easy to half-finish and worth restating, because both were found
broken after the fact:

* **Exact integers must be `Int64`, not `Float64`.** `Float64` is lossless for a value like a range
  index, but the moment one meets an integer literal `NumberMath` promotes the pair to `BigDecimal`.
  `std.range` elements, map-callback indices, `ByteArr` bytes and `std.count`/`std.find` results
  were all `Float64` initially, which cost 2.4–12× on array workloads.
* **Every number-to-string spelling must share `RenderUtils.renderNum`** — `std.toString`, `%s`,
  `%(key)s`, and `+` concatenation. Each has its own fast path, and three of the four were found
  narrowing independently.

`BigDecimal` is not uniformly slow, and knowing which operation is decides where tuning pays. Its
`+`, `-` and `*` are unremarkable; `/` is the outlier, at ~500-700ns, because on an exact quotient
it strips trailing zeros back to the preferred scale by repeated Knuth division. Anything that
divides per element therefore falls off a cliff the other operators do not have — which is the
whole of `cpp_suite/bench.06`. `NumberMath.divideExact` sidesteps it for the quotients that
actually occur (`/ 2`, `/ 4`, `/ 10`): a decimal quotient terminates iff scaling the numerator by
some `10^k` makes it divide evenly, and the smallest such `k` reproduces `BigDecimal./`'s own value
*and* scale, in `Long` arithmetic at ~10-45ns. Two things this depends on, both learned by
measuring:

* **Reject non-terminating quotients explicitly, but not on the first step.** A repeating quotient
  discovered by exhausting the loop pays for the whole trip *and* the fallback divide; over a sweep
  of divisors that measured as a 1.22× *regression*. `terminates` settles it in a few divisions —
  `10^k` can only ever supply factors of 2 and 5, so what remains of the divisor after those are
  stripped must already divide the dividend. It runs only after a scaling step has actually missed,
  because `/ 2`, `/ 5` and `/ 10` succeed on the first step and testing them first cost 3.5% on
  `bench.06`. Placement is purely a speed question: a `false` only routes to the exact divide.
* **The arms worth optimising are the `Dec128` ones.** With `floatAsBigDecimal` at its default
  `true`, a decimal literal parses as `Dec128`, so `Int64 / Dec128` and `Dec128 / Dec128` are what
  real programs execute. `Float64` operands arise only under the opt-out, from `-0.0`, and from
  double-returning `std` functions — optimising those arms alone measured as exactly nothing, and
  they no longer reach `BigDecimal` at all now that a `Float64` operand means IEEE-754.
