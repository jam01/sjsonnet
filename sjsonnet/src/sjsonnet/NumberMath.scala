package sjsonnet

import sjsonnet.Val.{Dec128, Float64, Int64}

import java.math.MathContext

import scala.util.control.NonFatal

/**
 * Cross-representation arithmetic and ordering for the three [[Val.Num]] representations
 * ([[Val.Int64]], [[Val.Float64]], [[Val.Dec128]]).
 *
 * Operands are promoted to the most precise representation needed to preserve correctness: `Int64`
 * stays `Int64` while the exact result fits in a `Long`, and anything mixing or overflowing widens
 * to `Dec128` (`BigDecimal` at `MathContext.DECIMAL128`).
 *
 * **Exactness wins, unconditionally.** A [[Val.Float64]] operand is promoted like any other, so a
 * `Float64` never survives an operation — it is an input representation only, arriving from `std`
 * results, from `-0`, or from an embedder. Promotion is not merely "keep more mantissa bits":
 * `BigDecimal.decimal(d)` reinterprets a double as its shortest round-tripping decimal, so it
 * changes the answer (`0.1 + 0.2` is `0.30000000000000004` raw and `0.3` promoted). `%` is the
 * non-obvious one — IEEE `fmod` is binary-exact and so looks safe, yet `0.3 % 0.1` is
 * `0.09999999999999998` raw and `0` promoted.
 *
 * The alternative, letting a `Float64` operand make the whole operation IEEE-754, was implemented
 * and reverted: it means one `std.sqrt` poisons every value downstream of it with no way back to
 * exactness, which is the wrong failure for a transformation tool. The cost of promoting instead is
 * that exact arithmetic over an already-inexact operand looks more precise than it is —
 * `std.sqrt(2) * std.sqrt(2)` is `2.00000000000000014481069235364401`. `std` is inexact by design
 * (`madr-better-nums.md`); reach for `xtr` when the value matters.
 *
 * Comparison never needs promotion — `decimal()` is order-preserving.
 *
 * PERF: these are the exact-but-slow paths, and all Jsonnet arithmetic routes through them
 * regardless of representation. The [[Evaluator]]'s raw-`Double` fast paths survive only for
 * comparisons and for bitwise/shift ops, which force their operands through `asSafeLong` anyway.
 *
 * PERF: Consider demoting resulting numbers.
 */
object NumberMath {

  def add(pos: Position, a: Val.Num, b: Val.Num)(implicit ev: EvalScope): Val.Num = {
    try {
      signZero(pos, box(pos, add(a, b)), isNegative(a) && isNegative(b))
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def add(a: Val.Num, b: Val.Num): Any = (a, b) match {
    case (Int64(_, x), Int64(_, y)) =>
      try { Math.addExact(x, y) }
      catch { case _: ArithmeticException => BigDecimal.decimal(x) + BigDecimal.decimal(y) }
    case (Int64(_, x), Float64(_, y)) => BigDecimal.decimal(x) + BigDecimal.decimal(y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) + y // Promote to BigDecimal

    case (Float64(_, x), Int64(_, y))   => BigDecimal.decimal(x) + BigDecimal.decimal(y)
    case (Float64(_, x), Float64(_, y)) =>
      BigDecimal.decimal(x) + BigDecimal.decimal(y)
    case (Float64(_, x), Dec128(_, y)) => BigDecimal.decimal(x) + y // Promote to Val.Dec128

    case (Dec128(_, x), Int64(_, y))   => x + BigDecimal.decimal(y)
    case (Dec128(_, x), Float64(_, y)) => x + BigDecimal.decimal(y)
    case (Dec128(_, x), Dec128(_, y))  => x + y // Val.Dec128 handles precision
  }

  def subtract(pos: Position, a: Val.Num, b: Val.Num)(implicit ev: EvalScope): Val.Num = {
    try {
      signZero(pos, box(pos, subtract(a, b)), isNegative(a) && !isNegative(b))
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def subtract(a: Val.Num, b: Val.Num): Any = (a, b) match {
    case (Int64(_, x), Int64(_, y)) =>
      try { Math.subtractExact(x, y) }
      catch { case _: ArithmeticException => BigDecimal.decimal(x) - BigDecimal.decimal(y) }
    case (Int64(_, x), Float64(_, y)) => BigDecimal.decimal(x) - BigDecimal.decimal(y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) - y

    case (Float64(_, x), Int64(_, y))   => BigDecimal.decimal(x) - BigDecimal.decimal(y)
    case (Float64(_, x), Float64(_, y)) =>
      BigDecimal.decimal(x) - BigDecimal.decimal(y)
    case (Float64(_, x), Dec128(_, y)) => BigDecimal.decimal(x) - y

    case (Dec128(_, x), Int64(_, y))   => x - BigDecimal.decimal(y)
    case (Dec128(_, x), Float64(_, y)) => x - BigDecimal.decimal(y)
    case (Dec128(_, x), Dec128(_, y))  => x - y
  }

  def multiply(pos: Position, a: Val.Num, b: Val.Num)(implicit ev: EvalScope): Val.Num = {
    try {
      signZero(pos, box(pos, multiply(a, b)), isNegative(a) != isNegative(b))
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def multiply(a: Val.Num, b: Val.Num): Any = (a, b) match {
    case (Int64(_, x), Int64(_, y)) =>
      try { Math.multiplyExact(x, y) }
      catch { case _: ArithmeticException => BigDecimal.decimal(x) * BigDecimal.decimal(y) }
    case (Int64(_, x), Float64(_, y)) => BigDecimal.decimal(x) * BigDecimal.decimal(y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) * y

    case (Float64(_, x), Int64(_, y))   => BigDecimal.decimal(x) * BigDecimal.decimal(y)
    case (Float64(_, x), Float64(_, y)) =>
      BigDecimal.decimal(x) * BigDecimal.decimal(y)
    case (Float64(_, x), Dec128(_, y)) => BigDecimal.decimal(x) * y

    case (Dec128(_, x), Int64(_, y))   => x * BigDecimal.decimal(y)
    case (Dec128(_, x), Float64(_, y)) => x * BigDecimal.decimal(y)
    case (Dec128(_, x), Dec128(_, y))  => x * y
  }

  def divide(pos: Position, a: Val.Num, b: Val.Num)(implicit ev: EvalScope): Val.Num = {
    if (b.isZero) Error.fail("Division by zero.", pos)
    try {
      signZero(pos, box(pos, divide(a, b)), isNegative(a) != isNegative(b))
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def divide(a: Val.Num, b: Val.Num): Any = (a, b) match {
    case (Int64(_, x), Int64(_, y)) =>
      if (x % y == 0)
        // Keep as Long if divisible — except Long.MinValue / -1, the one quotient that overflows
        // a Long despite dividing evenly (Math.divideExact needs Java 18+, so the one overflowing
        // case is checked directly rather than caught from an ArithmeticException).
        if (x == Long.MinValue && y == -1L) BigDecimal.decimal(x) / BigDecimal.decimal(y)
        else x / y
      else {
        // Promote to BigDecimal for precision, cheaply where the quotient allows.
        val exact = terminatingQuotient(x, 0, y, 0)
        if (exact != null) exact
        else BigDecimal.decimal(x) / BigDecimal.decimal(y)
      }
    case (Int64(_, x), Float64(_, y)) => divideExact(BigDecimal.decimal(x), BigDecimal.decimal(y))
    case (Int64(_, x), Dec128(_, y))  =>
      divideExact(BigDecimal.decimal(x), y) // Promote to BigDecimal

    case (Float64(_, x), Int64(_, y))   => divideExact(BigDecimal.decimal(x), BigDecimal.decimal(y))
    case (Float64(_, x), Float64(_, y)) =>
      divideExact(BigDecimal.decimal(x), BigDecimal.decimal(y))
    case (Float64(_, x), Dec128(_, y)) =>
      divideExact(BigDecimal.decimal(x), y) // Promote to BigDecimal

    case (Dec128(_, x), Int64(_, y)) =>
      divideExact(x, BigDecimal.decimal(y)) // Promote to BigDecimal
    case (Dec128(_, x), Float64(_, y)) =>
      divideExact(x, BigDecimal.decimal(y)) // Promote to BigDecimal
    case (Dec128(_, x), Dec128(_, y)) => divideExact(x, y) // BigDecimal handles precision
  }

  /** The range a `Long` can hold and still survive one more `* 10`. */
  private final val MaxScalableLong = Long.MaxValue / 10
  private final val MinScalableLong = Long.MinValue / 10

  /**
   * Digits an unscaled value may have and still be read out as a `Long`.
   *
   * Paired with the truncating `unscaledValue().longValue()` in [[divideExact]]: 18 digits keeps
   * the value under `10^18`, so the conversion is exact. Raising this silently truncates a wide
   * `Dec128` into a plausible-looking wrong quotient instead of falling back.
   */
  private final val MaxUnscaledDigits = 18

  /**
   * `x / y`, taking the cheap route when the quotient terminates within a `Long`.
   *
   * PERF: `BigDecimal./` costs ~500-700ns because its exact-remainder branch strips trailing zeros
   * back to the preferred scale by repeated Knuth division. [[terminatingQuotient]] settles the
   * same result in ~10-45ns for the quotients that arise most (`/ 2`, `/ 4`, `/ 10` and friends),
   * and falls back for the rest.
   */
  private def divideExact(x: BigDecimal, y: BigDecimal): BigDecimal = {
    val bx = x.bigDecimal
    val by = y.bigDecimal
    if (bx.precision() <= MaxUnscaledDigits && by.precision() <= MaxUnscaledDigits) {
      val exact = terminatingQuotient(
        bx.unscaledValue().longValue(),
        bx.scale(),
        by.unscaledValue().longValue(),
        by.scale()
      )
      if (exact != null) return exact
    }
    x / y
  }

  /**
   * The exact quotient of `ux * 10^-sx` and `uy * 10^-sy` as a `BigDecimal`, or `null` when it does
   * not terminate within a `Long`.
   *
   * A quotient terminates in decimal iff `ux * 10^k` is divisible by `uy` for some `k`, and the
   * first such `k` yields a quotient with no trailing zero — had `ux * 10^k / uy` ended in `0`,
   * `ux * 10^(k-1)` would already have divided evenly. That is exactly the representation
   * `BigDecimal./` arrives at, so the two agree on value *and* scale rather than merely comparing
   * equal. The scales ride along untouched: the result is that quotient at scale `k + sx - sy`.
   *
   * Callers must have ruled out `uy == 0`, and must keep `|ux|` under `10^19` so that `num / uy`
   * cannot overflow — [[divideExact]] does so via [[MaxUnscaledDigits]], and the `Int64 / Int64`
   * caller does so by having already returned on `x % y == 0`, which rules out `|y| < 2`.
   */
  private def terminatingQuotient(ux: Long, sx: Int, uy: Long, sy: Int): BigDecimal = {
    var num = ux
    var k = 0
    // |num| grows tenfold per iteration, so the guard always terminates the loop.
    while (true) {
      if (num % uy == 0) {
        val scale = k.toLong + sx.toLong - sy.toLong
        if (scale < Int.MinValue || scale > Int.MaxValue) return null
        return BigDecimal.decimal(
          java.math.BigDecimal.valueOf(num / uy, scale.toInt),
          MathContext.DECIMAL128
        )
      }
      // Deliberately not before the loop: divisors of 2, 5 and 10 resolve on the first scaling
      // step, and testing first would tax the commonest quotients to save the rarest.
      if (k >= 1 && !terminates(ux, uy)) return null
      if (num < MinScalableLong || num > MaxScalableLong) return null
      num *= 10
      k += 1
    }
    null // unreachable; `while (true)` is not a Nothing in Scala 2
  }

  /**
   * Whether `ux / uy` has a terminating decimal expansion.
   *
   * `uy` divides `ux * 10^k` for some `k` exactly when the part of `uy` coprime to 10 already
   * divides `ux` — the factors of 2 and 5 are what `10^k` can supply, nothing else is. Without this
   * test a repeating quotient costs 19 trips round the scaling loop *and* the fallback divide,
   * which measured as a 1.22x regression on `Dec128 / Int64` over a sweep of divisors.
   *
   * A `false` here only sends the caller to the exact `BigDecimal` divide, so where this is called
   * from is a speed question, not a correctness one.
   *
   * Callers must have ruled out `uy == 0`, which would not terminate below.
   */
  private def terminates(ux: Long, uy: Long): Boolean = {
    var coprimeTo10 = uy
    while (coprimeTo10 % 2 == 0) coprimeTo10 /= 2
    while (coprimeTo10 % 5 == 0) coprimeTo10 /= 5
    ux % coprimeTo10 == 0
  }

  def mod(pos: Position, a: Val.Num, b: Val.Num)(implicit ev: EvalScope): Val.Num = {
    if (b.isZero) Error.fail("Division by zero.", pos)
    try {
      // A zero remainder takes the sign of the dividend, as IEEE `fmod` does.
      signZero(pos, boxMod(pos, mod(a, b)), isNegative(a))
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def mod(a: Val.Num, b: Val.Num): Any = (a, b) match {
    // Int64 % Int64 is exact in `Long` and cannot overflow (|x % y| < |y|), so it skips BigDecimal
    // entirely — this is the hot path for the `%` operator over integers.
    case (Int64(_, x), Int64(_, y))   => x % y
    case (Int64(_, x), Float64(_, y)) => remainder(BigDecimal.decimal(x), BigDecimal.decimal(y))
    case (Int64(_, x), Dec128(_, y))  => remainder(BigDecimal.decimal(x), y)

    case (Float64(_, x), Int64(_, y))   => remainder(BigDecimal.decimal(x), BigDecimal.decimal(y))
    case (Float64(_, x), Float64(_, y)) =>
      remainder(BigDecimal.decimal(x), BigDecimal.decimal(y))
    case (Float64(_, x), Dec128(_, y)) => remainder(BigDecimal.decimal(x), y)

    case (Dec128(_, x), Int64(_, y))   => remainder(x, BigDecimal.decimal(y))
    case (Dec128(_, x), Float64(_, y)) => remainder(x, BigDecimal.decimal(y))
    case (Dec128(_, x), Dec128(_, y))  => remainder(x, y)
  }

  /**
   * Exact decimal remainder.
   *
   * `BigDecimal.%` applies `MathContext.DECIMAL128` to the *implied quotient*, and
   * `java.math.BigDecimal.remainder` raises `ArithmeticException("Division impossible")` whenever
   * that quotient needs more than 34 significant digits. That made `1e40 % 3` fail even though its
   * remainder is just `1`. Computing the remainder without a context has no such limit — `|a % b|`
   * is smaller than `|b|` by construction, so the result is always representable — and only the
   * re-wrap needs a context.
   */
  private def remainder(a: BigDecimal, b: BigDecimal): BigDecimal =
    new BigDecimal(
      a.bigDecimal.remainder(b.bigDecimal).round(MathContext.DECIMAL128),
      MathContext.DECIMAL128
    )

  def negate(pos: Position, a: Val.Num)(implicit ev: EvalScope): Val.Num = {
    try {
      signZero(pos, box(pos, negate(a)), !isNegative(a))
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  /**
   * Negation never promotes: it is exact in IEEE-754 and in decimal alike, so `Float64` stays
   * `Float64` (which is also what preserves `-0.0`).
   */
  private def negate(a: Val.Num): Any = a match {
    case Int64(_, x) =>
      try { Math.negateExact(x) }
      catch { case _: ArithmeticException => -BigDecimal.decimal(x) }
    case Float64(_, x) => -x
    case Dec128(_, x)  => -x
  }

  // Fold-time variants for StaticOptimizer: same arithmetic, but `null` instead of raising, since
  // constant folding must silently defer any failure to runtime rather than fail at parse time.

  def tryAdd(pos: Position, a: Val.Num, b: Val.Num): Val.Num =
    signZero(pos, tryBox(pos, add(a, b)), isNegative(a) && isNegative(b))
  def trySubtract(pos: Position, a: Val.Num, b: Val.Num): Val.Num =
    signZero(pos, tryBox(pos, subtract(a, b)), isNegative(a) && !isNegative(b))
  def tryMultiply(pos: Position, a: Val.Num, b: Val.Num): Val.Num =
    signZero(pos, tryBox(pos, multiply(a, b)), isNegative(a) != isNegative(b))
  def tryNegate(pos: Position, a: Val.Num): Val.Num =
    signZero(pos, tryBox(pos, negate(a)), !isNegative(a))

  def tryDivide(pos: Position, a: Val.Num, b: Val.Num): Val.Num =
    if (b.isZero) null
    else signZero(pos, tryBox(pos, divide(a, b)), isNegative(a) != isNegative(b))

  def tryMod(pos: Position, a: Val.Num, b: Val.Num): Val.Num =
    if (b.isZero) null
    else
      try signZero(pos, boxMod(pos, mod(a, b)), isNegative(a))
      catch { case NonFatal(_) => null }

  /**
   * Total ordering across all three representations.
   *
   * The all-[[Val.Float64]] case delegates to [[Util.compareDoubles]] so that IEEE-754 `-0.0`/`0.0`
   * equality (and NaN ordering) stays consistent with the rest of the evaluator rather than being
   * hand-rolled here. A `Float64` operand can carry `NaN` — the constructor only rejects `Infinite`
   * — so every cross-representation branch mixing in a `Float64` sorts it as greatest, matching
   * `Util.compareDoubles`, instead of promoting it to `BigDecimal.decimal`, which throws on `NaN`.
   */
  def compareTo(a: Val.Num, b: Val.Num): Int = (a, b) match {
    case (Int64(_, x), Int64(_, y))   => java.lang.Long.compare(x, y)
    case (Int64(_, x), Float64(_, y)) => compareLongToDouble(x, y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x).compare(y)

    case (Float64(_, x), Int64(_, y))   => -compareLongToDouble(y, x)
    case (Float64(_, x), Float64(_, y)) => Util.compareDoubles(x, y)
    case (Float64(_, x), Dec128(_, y))  => if (x.isNaN) 1 else BigDecimal.decimal(x).compare(y)

    case (Dec128(_, x), Int64(_, y))   => x.compare(BigDecimal.decimal(y))
    case (Dec128(_, x), Float64(_, y)) => if (y.isNaN) -1 else x.compare(BigDecimal.decimal(y))
    case (Dec128(_, x), Dec128(_, y))  => x.compare(y)
  }

  /**
   * Order an exact `Long` against a `Double`, without allocating in the common case.
   *
   * Mixing the two representations is ordinary rather than exotic: `std.*` returns [[Val.Float64]]
   * while ranges, indices and integer literals are [[Val.Int64]], so `std.assertEqual` over arrays
   * and `std.sort` over mixed sources land here on every element. The `BigDecimal` fallback costs
   * about 600ns and four objects per comparison, which is why the whole-number case is split out.
   *
   * The fast path is not an approximation: a double that is exactly an integer has exactly that
   * integer as its shortest round-tripping decimal, so `Long.compare` and the `BigDecimal`
   * comparison agree wherever [[RenderUtils.isExactLongDouble]] holds. Everything it rejects —
   * fractional values, magnitudes outside `Long`, and `-0.0` — still takes the exact path, so
   * `-0.0` keeps comparing equal to `0`.
   *
   * Nothing here allocates: `l` is a primitive local and `Long.compare` is a static intrinsic.
   *
   * `y.isNaN` is checked first since `BigDecimal.decimal(Double.NaN)` throws rather than compares.
   */
  @inline private def compareLongToDouble(x: Long, y: Double): Int = {
    if (y.isNaN) -1
    else {
      val l = y.toLong
      if (RenderUtils.isExactLongDouble(y, l)) java.lang.Long.compare(x, l)
      else BigDecimal.decimal(x).compare(BigDecimal.decimal(y))
    }
  }

  /** The IEEE-754 sign of an operand, `-0.0` included. */
  private[sjsonnet] def isNegative(n: Val.Num): Boolean = n match {
    case Int64(_, x)   => x < 0
    case Float64(_, x) => java.lang.Double.doubleToRawLongBits(x) < 0
    case Dec128(_, x)  => x.signum < 0
  }

  /**
   * Re-applies IEEE-754 signed-zero semantics to an exact result.
   *
   * Neither `Long` nor `BigDecimal` has a signed zero, so a zero result that IEEE would have signed
   * negative is re-boxed as `Float64(-0.0)` — the only representation that can carry it. This is
   * load-bearing, not a curiosity: Jsonnet has no negative literal, so `-0` is unary minus applied
   * to `Int64(0)`, and `0 * -1` is the canonical way to obtain a negative zero. Without this,
   * `{a: -0}` would render as `0` and `std.atan2(0 * -1, -1)` would flip sign.
   */
  private def signZero(pos: Position, r: Val.Num, negative: Boolean): Val.Num =
    if (negative && r != null && r.isZero) Val.Float64(pos, -0.0) else r

  /** Wraps the widest-representation result of an arithmetic op back into a [[Val.Num]]. */
  private def box(pos: Position, result: Any): Val.Num = result match {
    case x: Long       => Val.Int64(pos, x)
    case x: Double     => Val.Float64(pos, x)
    case x: BigDecimal => Val.Dec128(pos, x)
    case other         => throw new IllegalStateException("Unexpected numeric result: " + other)
  }

  /**
   * [[box]] for remainders, which demote back to `Int64` whenever the exact result fits a `Long`.
   */
  private def boxMod(pos: Position, result: Any): Val.Num = result match {
    case x: Long                        => Val.Int64(pos, x)
    case x: Double                      => Val.Float64(pos, x)
    case x: BigDecimal if x.isValidLong => Val.Int64(pos, x.longValue)
    case x: BigDecimal                  => Val.Dec128(pos, x)
    case other => throw new IllegalStateException("Unexpected numeric result: " + other)
  }

  private def tryBox(pos: Position, result: => Any): Val.Num =
    try box(pos, result)
    catch { case NonFatal(_) => null }
}
