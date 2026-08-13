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
 * PERF: these are the exact-but-slow paths, and all Jsonnet arithmetic routes through them
 * regardless of representation — see [[promoteFloat64Arithmetic]] for why `Float64 ⊕ Float64`
 * cannot stay in raw `Double`. The [[Evaluator]]'s raw-`Double` fast paths survive only for
 * comparisons and for bitwise/shift ops, which force their operands through `asSafeLong` anyway.
 *
 * PERF: Consider demoting resulting numbers.
 */
object NumberMath {

  /**
   * The single gate for "may `Float64 ⊕ Float64` arithmetic stay in raw `Double`?".
   *
   * It is `true` today: promotion via `BigDecimal.decimal(d)` reinterprets a double as its shortest
   * round-tripping decimal, so skipping it does not merely keep more mantissa bits, it changes the
   * answer (`0.1 + 0.2` is `0.30000000000000004` raw and `0.3` promoted). `%` is the non-obvious
   * one: IEEE `fmod` is always binary-exact, yet `0.3 % 0.1` is `0.09999999999999998` raw and `0`
   * promoted, so it promotes too. Comparisons never need it — `decimal()` is order-preserving.
   *
   * `sjsonnet.floatAsBigDecimal` deliberately does NOT control this; it is a parsing/representation
   * switch only. If a genuine arithmetic-speed escape hatch is ever wanted it gets its own flag,
   * and **this val is the only thing that has to change** — every promotion decision routes here.
   */
  private[sjsonnet] val promoteFloat64Arithmetic: Boolean = true

  /**
   * Whether the literal text `s0` (with `.` at `dotIndex` and `e`/`E` at `expIndex`, or -1 when
   * absent) is safely representable as an IEEE-754 double: at most `maxSig` significant mantissa
   * digits and an exponent within `[minExp, maxExp]`.
   */
  private[sjsonnet] def allowFloat64LiteralWithIndexes(
      s0: CharSequence,
      dotIndex: Int,
      expIndex: Int,
      maxSig: Int = 17,
      minExp: Int = -325,
      maxExp: Int = 325): Boolean = {
    val s = s0
    val n = s.length()

    // mantissa scan range: [start, stop)
    var i = 0
    if (i < n) {
      val c = s.charAt(0)
      if (c == '+' || c == '-') i = 1
    }
    val stop = if (expIndex >= 0) expIndex else n

    // Count significant digits in mantissa (ignore '.', ignore leading zeros)
    var sig = 0
    var seenNonZero = false
    while (i < stop) {
      val c = s.charAt(i)
      if (c != '.') {
        // assume digits only here (parser already validated)
        if (seenNonZero) {
          sig += 1
          if (sig > maxSig) return false
        } else if (c != '0') {
          seenNonZero = true
          sig = 1
        }
      }
      i += 1
    }

    // Exponent bounds check (only if exp exists)
    if (expIndex >= 0) {
      var j = expIndex + 1
      if (j >= n) return false // malformed, but be defensive

      var sign = 1
      val c0 = s.charAt(j)
      if (c0 == '+') j += 1
      else if (c0 == '-') {
        sign = -1
        j += 1
      }

      // parse exponent digits with early bound checks
      var exp = 0
      while (j < n) {
        val d = s.charAt(j) - '0'
        exp = exp * 10 + d
        // early exit if already out of bounds
        val signed = exp * sign
        if (signed < minExp || signed > maxExp) return false
        j += 1
      }
    }

    true
  }

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
      if (promoteFloat64Arithmetic) BigDecimal.decimal(x) + BigDecimal.decimal(y) else x + y
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
      if (promoteFloat64Arithmetic) BigDecimal.decimal(x) - BigDecimal.decimal(y) else x - y
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
      if (promoteFloat64Arithmetic) BigDecimal.decimal(x) * BigDecimal.decimal(y) else x * y
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
      if (x % y == 0) x / y // Keep as Long if divisible
      else {
        val exact = terminatingLongQuotient(x, y)
        if (exact != null) exact
        else BigDecimal.decimal(x) / BigDecimal.decimal(y) // Promote to BigDecimal for precision
      }
    case (Int64(_, x), Float64(_, y)) => BigDecimal.decimal(x) / BigDecimal.decimal(y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) / y // Promote to BigDecimal

    case (Float64(_, x), Int64(_, y))   => BigDecimal.decimal(x) / BigDecimal.decimal(y)
    case (Float64(_, x), Float64(_, y)) =>
      if (promoteFloat64Arithmetic) BigDecimal.decimal(x) / BigDecimal.decimal(y) else x / y
    case (Float64(_, x), Dec128(_, y)) => BigDecimal.decimal(x) / y // Promote to BigDecimal

    case (Dec128(_, x), Int64(_, y))   => x / BigDecimal.decimal(y) // Promote to BigDecimal
    case (Dec128(_, x), Float64(_, y)) => x / BigDecimal.decimal(y) // Promote to BigDecimal
    case (Dec128(_, x), Dec128(_, y))  => x / y // BigDecimal handles precision
  }

  /** The range a `Long` can hold and still survive one more `* 10`. */
  private final val MaxScalableLong = Long.MaxValue / 10
  private final val MinScalableLong = Long.MinValue / 10

  /**
   * The exact quotient `x / y` as a `BigDecimal`, or `null` when it does not terminate within a
   * `Long`.
   *
   * PERF: this exists to keep `x / 2`, `x / 4`, `x / 10` and friends off `BigDecimal.divide`, which
   * costs ~700ns because its exact-remainder branch strips trailing zeros by repeated Knuth
   * division. Pure `Long` arithmetic settles the same cases in ~30-45ns, and a miss (a repeating
   * quotient such as `x / 3`) wastes at most 19 multiply-and-remainder pairs before falling back.
   *
   * `x / y` has a terminating decimal expansion iff `x * 10^s` is divisible by `y` for some `s`.
   * The first such `s` yields a quotient with no trailing zero — if `x * 10^s / y` ended in `0`
   * then `x * 10^(s-1)` would already have divided evenly — so the scale matches the one
   * `BigDecimal.divide(_, DECIMAL128)` settles on, and the two agree on value *and* scale.
   *
   * Callers must have ruled out `y == 0` and `x % y == 0`; the latter also rules out `|y| < 2`,
   * which is what keeps `num / y` from overflowing.
   */
  private def terminatingLongQuotient(x: Long, y: Long): BigDecimal = {
    var num = x
    var scale = 0
    // |num| grows tenfold per iteration, so the guard always terminates the loop.
    while (num >= MinScalableLong && num <= MaxScalableLong) {
      num *= 10
      scale += 1
      if (num % y == 0)
        return BigDecimal.decimal(
          java.math.BigDecimal.valueOf(num / y, scale),
          MathContext.DECIMAL128
        )
    }
    null
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
      if (promoteFloat64Arithmetic) remainder(BigDecimal.decimal(x), BigDecimal.decimal(y))
      else x % y
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
   * hand-rolled here.
   */
  def compareTo(a: Val.Num, b: Val.Num): Int = (a, b) match {
    case (Int64(_, x), Int64(_, y))   => java.lang.Long.compare(x, y)
    case (Int64(_, x), Float64(_, y)) => compareLongToDouble(x, y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x).compare(y)

    case (Float64(_, x), Int64(_, y))   => -compareLongToDouble(y, x)
    case (Float64(_, x), Float64(_, y)) => Util.compareDoubles(x, y)
    case (Float64(_, x), Dec128(_, y))  => BigDecimal.decimal(x).compare(y)

    case (Dec128(_, x), Int64(_, y))   => x.compare(BigDecimal.decimal(y))
    case (Dec128(_, x), Float64(_, y)) => x.compare(BigDecimal.decimal(y))
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
   */
  @inline private def compareLongToDouble(x: Long, y: Double): Int = {
    val l = y.toLong
    if (RenderUtils.isExactLongDouble(y, l)) java.lang.Long.compare(x, l)
    else BigDecimal.decimal(x).compare(BigDecimal.decimal(y))
  }

  /** The IEEE-754 sign of an operand, `-0.0` included. */
  private def isNegative(n: Val.Num): Boolean = n match {
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
