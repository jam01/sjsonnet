package sjsonnet

import sjsonnet.Val.{Int64, Float64, Dec128}

import java.math.MathContext

// PERF: Consider demoting resulting numbers
object NumberMath {
  private[sjsonnet] def allowFloat64LiteralWithIndexes(
                                      s0: CharSequence,
                                      dotIndex: Int,
                                      expIndex: Int,
                                      maxSig: Int = 17,
                                      minExp: Int = -325,
                                      maxExp: Int = 325
                                    ): Boolean = {
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
    if (!seenNonZero) sig = 1 // treat 0 / 0.0 / .0 as "1 significant digit"

    // Exponent bounds check (only if exp exists)
    if (expIndex >= 0) {
      var j = expIndex + 1
      if (j >= n) return false // malformed, but be defensive

      var sign = 1
      val c0 = s.charAt(j)
      if (c0 == '+') j += 1
      else if (c0 == '-') {
        sign = -1; j += 1
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

  def add(pos: Position, a: Val.Num, b: Val.Num)(implicit evaluator: EvalScope): Val.Num = {
    try {
      add(a, b) match
        case x: Long       => Val.Int64(pos, x)
        case x: Double     => Val.Float64(pos, x)
        case x: BigDecimal => Val.Dec128(pos, x)
        case x: BigInt     => Val.Dec128(pos, BigDecimal(x, MathContext.DECIMAL128))
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def add(a: Val.Num, b: Val.Num): Any = (a, b) match {
    case (Int64(_, x), Int64(_, y)) =>
      try { Math.addExact(x, y) }
      catch { case _: ArithmeticException => BigInt(x) + BigInt(y) }
    case (Int64(_, x), Float64(_, y)) => BigDecimal.decimal(x) + BigDecimal.decimal(y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) + y // Promote to BigDecimal

    case (Float64(_, x), Int64(_, y))   => BigDecimal.decimal(x) + BigDecimal.decimal(y)
    case (Float64(_, x), Float64(_, y)) => BigDecimal.decimal(x) + BigDecimal.decimal(y)
    case (Float64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) + y // Promote to Val.Dec128

    case (Dec128(_, x), Int64(_, y))   => x + BigDecimal.decimal(y)
    case (Dec128(_, x), Float64(_, y)) => x + BigDecimal.decimal(y)
    case (Dec128(_, x), Dec128(_, y))  => x + y // Val.Dec128 handles precision
  }

  def divide(pos: Position, a: Val.Num, b: Val.Num)(implicit evaluator: EvalScope): Val.Num = {
    try {
      divide(a, b) match
        case x: Long       => Val.Int64(pos, x)
        case x: Double     => Val.Float64(pos, x)
        case x: BigDecimal => Val.Dec128(pos, x)
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def divide(a: Val.Num, b: Val.Num): Any = (a, b) match {
    case (Int64(_, x), Int64(_, y)) =>
      if (x % y == 0) x / y // Keep as Long if divisible
      else BigDecimal.decimal(x) / BigDecimal.decimal(y) // Promote to BigDecimal for precision
    case (Int64(_, x), Float64(_, y)) => BigDecimal.decimal(x) / BigDecimal.decimal(y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) / y // Promote to BigDecimal

    case (Float64(_, x), Int64(_, y))   => BigDecimal.decimal(x) / BigDecimal.decimal(y)
    case (Float64(_, x), Float64(_, y)) => BigDecimal.decimal(x) / BigDecimal.decimal(y)
    case (Float64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) / y // Promote to BigDecimal

    case (Dec128(_, x), Int64(_, y))   => x / BigDecimal.decimal(y) // Promote to BigDecimal
    case (Dec128(_, x), Float64(_, y)) => x / BigDecimal.decimal(y) // Promote to BigDecimal
    case (Dec128(_, x), Dec128(_, y))  => x / y // BigDecimal handles precision
  }

  def subtract(pos: Position, a: Val.Num, b: Val.Num)(implicit evaluator: EvalScope): Val.Num = {
    try {
      subtract(a, b) match
        case x: Long       => Val.Int64(pos, x)
        case x: Double     => Val.Float64(pos, x)
        case x: BigDecimal => Val.Dec128(pos, x)
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def subtract(a: Val.Num, b: Val.Num): Any = (a, b) match {
    case (Int64(_, x), Int64(_, y)) =>
      try { Math.subtractExact(x, y) }
      catch { case _: ArithmeticException => BigInt(x) - BigInt(y) }
    case (Int64(_, x), Float64(_, y)) => BigDecimal.decimal(x) - BigDecimal.decimal(y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) - y

    case (Float64(_, x), Int64(_, y))   => BigDecimal.decimal(x) - BigDecimal.decimal(y)
    case (Float64(_, x), Float64(_, y)) => BigDecimal.decimal(x) - BigDecimal.decimal(y)
    case (Float64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) - y

    case (Dec128(_, x), Int64(_, y))   => x - BigDecimal.decimal(y)
    case (Dec128(_, x), Float64(_, y)) => x - BigDecimal.decimal(y)
    case (Dec128(_, x), Dec128(_, y))  => x - y
  }

  def multiply(pos: Position, a: Val.Num, b: Val.Num)(implicit evaluator: EvalScope): Val.Num = {
    try {
      multiply(a, b) match
        case x: Long       => Val.Int64(pos, x)
        case x: Double     => Val.Float64(pos, x)
        case x: BigDecimal => Val.Dec128(pos, x)
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def multiply(a: Val.Num, b: Val.Num): Any = (a, b) match {
    case (Int64(_, x), Int64(_, y)) =>
      try { Math.multiplyExact(x, y) }
      catch { case _: ArithmeticException => BigInt(x) * BigInt(y) }
    case (Int64(_, x), Float64(_, y)) => BigDecimal.decimal(x) * BigDecimal.decimal(y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) * y

    case (Float64(_, x), Int64(_, y))   => BigDecimal.decimal(x) * BigDecimal.decimal(y)
    case (Float64(_, x), Float64(_, y)) => BigDecimal.decimal(x) * BigDecimal.decimal(y)
    case (Float64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) * y

    case (Dec128(_, x), Int64(_, y))   => x * BigDecimal.decimal(y)
    case (Dec128(_, x), Float64(_, y)) => x * BigDecimal.decimal(y)
    case (Dec128(_, x), Dec128(_, y))  => x * y
  }

  // General modulo function
  def mod(pos: Position, a: Val.Num, b: Val.Num)(implicit evaluator: EvalScope): Val.Num = {
    try {
      mod(a, b) match // downcasting
        case x: BigDecimal if x.isValidLong => Val.Int64(pos, x.longValue)
//        case x: BigDecimal if x.isExactDouble => Val.Float64(pos, x.doubleValue)
        case x: BigDecimal => Val.Dec128(pos, x)
    } catch {
      case e: ArithmeticException      => Error.fail(e.getMessage, pos)
      case e: IllegalArgumentException => Error.fail(e.getMessage, pos)
    }
  }

  private def mod(a: Val.Num, b: Val.Num): BigDecimal = (a, b) match {
    case (Int64(_, x), Int64(_, y))   => BigDecimal.decimal(x) % BigDecimal.decimal(y)
    case (Int64(_, x), Float64(_, y)) => BigDecimal.decimal(x) % BigDecimal.decimal(y)
    case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) % y

    case (Float64(_, x), Int64(_, y))   => BigDecimal.decimal(x) % BigDecimal.decimal(y)
    case (Float64(_, x), Float64(_, y)) => BigDecimal.decimal(x) % BigDecimal.decimal(y)
    case (Float64(_, x), Dec128(_, y))  => BigDecimal.decimal(x) % y

    case (Dec128(_, x), Int64(_, y))   => x % BigDecimal.decimal(y)
    case (Dec128(_, x), Float64(_, y)) => x % BigDecimal.decimal(y)
    case (Dec128(_, x), Dec128(_, y))  => x % y
  }

  def compareTo(a: Val.Num, b: Val.Num): Int = {
    (a, b) match {
      case (Int64(_, x), Int64(_, y))   => x.compareTo(y)
      case (Int64(_, x), Float64(_, y)) => _64(x, y)
      case (Int64(_, x), Dec128(_, y))  => BigDecimal.decimal(x).compareTo(y)

      case (Float64(_, x), Int64(_, y))   => -_64(y, x)
      case (Float64(_, x), Float64(_, y)) =>
        if (x == 0 && y == -0) return 0
        if (x == -0 && y == 0) return 0
        BigDecimal.decimal(x).compareTo(BigDecimal.decimal(y))
      case (Float64(_, x), Dec128(_, y)) => BigDecimal.decimal(x).compareTo(y)

      case (Dec128(_, x), Int64(_, y))   => x.compareTo(BigDecimal.decimal(y))
      case (Dec128(_, x), Float64(_, y)) => x.compareTo(BigDecimal.decimal(y))
      case (Dec128(_, x), Dec128(_, y))  => x.compareTo(y)
    }
  }

  private def _64(x: Long, y: Double): Int = {
    BigDecimal.decimal(x).compareTo(BigDecimal.decimal(y)) // Handle precision or overflow
  }
}
