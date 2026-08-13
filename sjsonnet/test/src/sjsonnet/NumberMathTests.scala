package sjsonnet

import utest.{ArrowAssert, TestSuite, Tests, test}

import java.math.MathContext

/**
 * Semantics of the three-representation numeric model — see `madr-better-nums.md`.
 *
 * These assertions deliberately avoid `Interpreter.interpret`, whose `ujson.Value` result stores
 * every number as a `Double` and so cannot express an exact `Int64` past 2^53 or a `Dec128`
 * outside binary64's range. Values are inspected as `Val`s, or rendered to text via the same
 * renderer the CLI uses.
 */
object NumberMathTests extends TestSuite {

  def tests: Tests = Tests {

    test("literal representation") {
      test("integers are Int64 while they fit a Long") {
        assertKind(evalNum("0"), "int64")
        assertKind(evalNum("123"), "int64")
        // 2^53 + 1: exactly the value a Double cannot hold.
        assertKind(evalNum("9007199254740993"), "int64")
        assertKind(evalNum("9223372036854775807"), "int64")
      }

      test("integers wider than a Long widen to Dec128, not Float64") {
        // #1019's point was "don't crash"; Dec128 satisfies that and stays exact to 34 digits.
        val v = evalNum("9223372036854775808")
        assertKind(v, "dec128")
        render(v) ==> "9223372036854775808"
      }

      test("decimal literals are Dec128 by default") {
        assertKind(evalNum("0.1"), "dec128")
        assertKind(evalNum("1.25"), "dec128")
        // An exponent alone is enough — no decimal point required.
        assertKind(evalNum("1e3"), "dec128")
      }

      test("Val.Num builds the same representations from literal text") {
        assert(Val.Num(null, "0.1").isInstanceOf[Val.Dec128])
        assert(Val.Num(null, "9007199254740993").isInstanceOf[Val.Int64])
        assert(Val.Num(null, "9223372036854775808").isInstanceOf[Val.Dec128])
      }

      test("Dec128 rounds at DECIMAL128 precision") {
        val v = evalNum("1.23456789012345678901234567890123456789")
        assertKind(v, "dec128")
        // 34 significant digits, half-even.
        render(v) ==> "1.234567890123456789012345678901235"
      }
    }

    test("negative zero") {
      // Neither Long nor BigDecimal has a signed zero, so every -0 must land in Float64 — the one
      // representation that can carry it. This is the regression the whole rework exists to
      // prevent: upstream deliberately fixed bare `-0` rendering (#926), and a naive port of the
      // Int64/Dec128 split silently reintroduces the bug.

      test("bare -0 literal text keeps its sign") {
        // The case the original patch never covered: no decimal point, no exponent. Routing this
        // to Int64(0) — the obvious reading of "integers are Int64" — loses the sign outright.
        val v = Val.Num(null, "-0")
        assertKind(v, "float64")
        assert(java.lang.Double.compare(v.rawDouble, -0.0) == 0)
        render(v) ==> "-0"
      }

      test("the rule generalizes past bare -0") {
        for (text <- Seq("-0", "-0.0", "-0.00", "-0.00e5", "-0E10")) {
          val v = Val.Num(null, text)
          assertKind(v, "float64")
          assert(java.lang.Double.compare(v.rawDouble, -0.0) == 0)
          render(v) ==> "-0"
        }
        // Positive zero is unaffected and stays an exact integer.
        assertKind(Val.Num(null, "0"), "int64")
        render(Val.Num(null, "0")) ==> "0"
      }

      test("survives ValVisitor ingest and materialization") {
        // std.parseJson is the ValVisitor path, i.e. the one the -0 rule was written for.
        renderExpr("""std.parseJson("-0")""") ==> "-0"
        renderExpr("""std.parseJson("-0.0")""") ==> "-0"
        renderExpr("""std.parseJson("-0.00e5")""") ==> "-0"
        renderExpr("""std.parseJson("{\"a\":-0,\"b\":0}")""") ==> """{"a": -0, "b": 0}"""
      }

      test("arithmetic re-applies IEEE-754 signed-zero rules") {
        // Jsonnet has no negative literal, so source `-0` is unary minus over Int64(0), and
        // `0 * -1` is the canonical way to produce one. NumberMath.signZero re-boxes these.
        renderExpr("-0") ==> "-0"
        renderExpr("0 * -1") ==> "-0"
        renderExpr("-0 + -0") ==> "-0" // both negative
        renderExpr("0 - 0") ==> "0" // left is positive
        renderExpr("-0 - 0") ==> "-0" // left negative only
        renderExpr("-6 % 3") ==> "-0" // dividend's sign
        renderExpr("{a: -0}") ==> """{"a": -0}"""
      }

      test("compares equal to positive zero") {
        val pz = evalNum("0.0")
        val nz = evalNum("-(0.0)")
        assert(NumberMath.compareTo(pz, nz) == 0)
        assert(NumberMath.compareTo(nz, pz) == 0)
        eval("-0.0 == 0.0") ==> ujson.True
        // #913: primitiveEquals(-0.0, 0.0) is true, and stayed true through the rework.
        eval("std.primitiveEquals(-0, 0)") ==> ujson.True
      }
    }

    test("arithmetic") {
      test("Int64 + Int64 stays Int64 until it overflows") {
        assertKind(evalNum("1 + 2"), "int64")
        assertKind(evalNum("9223372036854775807 - 1"), "int64")
        // Long.MaxValue + 1 must widen rather than wrap.
        val v = evalNum("9223372036854775807 + 1")
        assertKind(v, "dec128")
        render(v) ==> "9223372036854775808"
      }

      test("negating Long.MinValue widens instead of overflowing") {
        // -9223372036854775808 is unary minus over a Dec128 literal (the magnitude is past
        // Long.MaxValue), so this is really -(-(2^63)).
        val v = evalNum("-(-9223372036854775808)")
        assertKind(v, "dec128")
        v match {
          case Val.Dec128(_, bd) =>
            assert(bd == BigDecimal("9223372036854775808", MathContext.DECIMAL128))
          case other => throw new AssertionError(s"Expected Dec128, got $other")
        }
      }

      test("negating a normal Int64 stays Int64") {
        val v = evalNum("-123")
        assertKind(v, "int64")
        v.asLong ==> -123L
      }

      test("division of divisible integers stays Int64") {
        assertKind(evalNum("4 / 2"), "int64")
        eval("4 / 2") ==> ujson.Num(2)
      }

      test("division of indivisible integers widens to Dec128") {
        val v = evalNum("1 / 2")
        assertKind(v, "dec128")
        assertNumEq(v, evalNum("0.5"))
      }

      test("non-terminating division rounds at DECIMAL128") {
        val v = evalNum("1 / 3")
        assertKind(v, "dec128")
        render(v) ==> "0.3333333333333333333333333333333333"
      }

      test("decimal arithmetic is exact, not binary64") {
        // The headline case: 0.1 + 0.2 is 0.30000000000000004 in doubles.
        assertNumEq(evalNum("0.1 + 0.2"), evalNum("0.3"))
        render(evalNum("0.1 + 0.2")) ==> "0.3"
        render(evalNum("1.5 + 0.5")) ==> "2" // scale 1 spells itself "2.0"; stripTrailingZeros
      }

      test("mixed representations promote") {
        assertNumEq(evalNum("1 + 0.5"), evalNum("1.5"))
        assertKind(evalNum("1 + 0.5"), "dec128")
      }

      test("a Float64 operand makes the whole operation IEEE-754") {
        // Under the default floatAsBigDecimal=true the only Float64s are std results and -0, so
        // std.sqrt is the way in. Promoting one does exact decimal work on an operand that never
        // carried decimal meaning: this used to spell 2.00000000000000014481069235364401, 33
        // digits manufactured from a value with ~16 significant ones.
        render(evalNum("std.sqrt(2) * std.sqrt(2)")) ==> "2.0000000000000004"
        assertKind(evalNum("std.sqrt(2) * std.sqrt(2)"), "float64")
      }

      test("inexactness propagates instead of being re-exactified") {
        // Five spellings of 2*sqrt(2), which must agree. Confining raw arithmetic to
        // both-operands-Float64 split the first from the rest, because every other spelling
        // re-entered the exact core the moment it met a literal.
        val doubled = "2.8284271247461903"
        render(evalNum("std.sqrt(2) + std.sqrt(2)")) ==> doubled
        render(evalNum("std.sqrt(2) * 2")) ==> doubled
        render(evalNum("std.sqrt(2) * 2.0")) ==> doubled
        render(evalNum("2 * std.sqrt(2)")) ==> doubled
        render(evalNum("2.0 * std.sqrt(2)")) ==> doubled
        assertKind(evalNum("std.sqrt(2) * 2"), "float64")
        assertKind(evalNum("2.0 * std.sqrt(2)"), "float64")
        // Subtraction, division and modulo take the same route.
        render(evalNum("std.sqrt(2) / 2")) ==> "0.7071067811865476"
        render(evalNum("std.floor(7.5) % 2")) ==> "1"
        render(evalNum("std.sum([0.1, 0.2]) * 10")) ==> "3.0000000000000004"
      }

      test("the exact tiers are untouched by the float rule") {
        // Literals are Dec128, so nothing above changes what ordinary arithmetic spells.
        render(evalNum("0.1 + 0.2")) ==> "0.3"
        render(evalNum("9223372036854775807 + 1")) ==> "9223372036854775808"
        assertKind(evalNum("0.1 + 0.2"), "dec128")
        assertKind(evalNum("2 * 3"), "int64")
      }

      test("results outside binary64 range are constructible") {
        render(evalNum("1e308 + 1e308")) ==> "2e+308"
        render(evalNum("1e300 * 1000000000")) ==> "1e+309"
        render(evalNum("1 / 1e30")) ==> "1e-30"
      }

      test("modulo") {
        val i = evalNum("5 % 2")
        assertKind(i, "int64")
        i.asLong ==> 1L
        assertNumEq(evalNum("5.5 % 2"), evalNum("1.5"))
        // 0.3 % 0.1 is 0.09999999999999998 under IEEE fmod, 0 in decimal.
        render(evalNum("0.3 % 0.1")) ==> "0"
        // BigDecimal.remainder throws "Division impossible" when the implied quotient exceeds 34
        // digits, even though the remainder is trivially representable.
        render(evalNum("1e40 % 3")) ==> "1"
        render(evalNum("1e400 % 3")) ==> "1"
        // Sign follows the dividend.
        render(evalNum("-7 % 3")) ==> "-1"
        render(evalNum("7 % -3")) ==> "1"
        render(evalNum("9223372036854775807 % 1000")) ==> "807"
      }

      test("division and modulo by zero report upstream's message") {
        assert(evalErr("5 / 0").contains("Division by zero."))
        assert(evalErr("5 % 0").contains("Division by zero."))
      }
    }

    test("comparison and equality") {
      test("equality holds across representations") {
        val a = evalNum("1") // Int64
        val b = evalNum("1.0") // Dec128
        val c = evalNum("1.00") // Dec128, different scale
        assertNumEq(a, b)
        assertNumEq(b, c)
        assertNumEq(a, c)
        eval("1 == 1.0") ==> ujson.True
        eval("1.0 == 1.00") ==> ujson.True
      }

      test("distinct integers past 2^53 do not compare equal") {
        // They share a Double, so any narrowing comparison reports them equal.
        val a = evalNum("9007199254740993")
        val b = evalNum("9007199254740994")
        assert(NumberMath.compareTo(a, b) < 0)
        assert(NumberMath.compareTo(b, a) > 0)
        eval("9007199254740993 == 9007199254740994") ==> ujson.False
        eval("std.primitiveEquals(9007199254740993, 9007199254740994)") ==> ujson.False
      }

      test("std.sort preserves Int64 ordering above 2^53") {
        val vs = evalVal("std.sort([9007199254740994, 9007199254740993])").asArr.asStrictArray
        vs.length ==> 2
        val a = vs(0).asNum
        val b = vs(1).asNum
        assert(NumberMath.compareTo(a, b) < 0)
        assertKind(a, "int64")
        assertKind(b, "int64")
        // Sorting must hand back the caller's own values, not doubles rebuilt from them.
        render(a) ==> "9007199254740993"
        render(b) ==> "9007199254740994"
      }

      test("std.setUnion does not merge distinct values") {
        renderExpr("std.setUnion([9007199254740993], [9007199254740994])") ==>
        "[9007199254740993, 9007199254740994]"
      }
    }

    test("float64 literal admission") {
      // allowFloat64LiteralWithIndexes decides whether a literal is safe for the Float64
      // representation opt-out (sjsonnet.floatAsBigDecimal=false). It is a text predicate: at
      // most 17 significant mantissa digits, exponent within [-325, 325].
      def ok(s: String): Unit = {
        val (dot, exp) = indices(s)
        assert(NumberMath.allowFloat64LiteralWithIndexes(s, dot, exp))
      }
      def bad(s: String): Unit = {
        val (dot, exp) = indices(s)
        assert(!NumberMath.allowFloat64LiteralWithIndexes(s, dot, exp))
      }

      test("short mantissas are admitted") { ok("0.1") }
      test("signed literals are admitted") { ok("-0.1") }
      test("long mantissas are rejected") { bad("1.23456789012345678") }
      test("leading zeros are not significant digits") { ok("000.0001") }
      test("all-zero forms are admitted") {
        for (s <- Seq("0", "0.0", "0000.0000")) ok(s)
      }
      test("exponent bounds are inclusive") {
        ok("1e+325"); bad("1e+326")
        ok("1e-325"); bad("1e-326")
      }
    }

    test("rendering") {
      test("dot and exponent variants render canonically") {
        render(evalNum("1000")) ==> "1000"
        render(evalNum("1E+3")) ==> "1000"
        render(evalNum("1.25")) ==> "1.25"
        // Not Java's "1.0E-30": renderDec128 normalizes to sjsonnet's lowercase-e spelling.
        render(evalNum("1.0E-30")) ==> "1e-30"
      }

      test("whole values expand up to the 1e21 fixed-notation window") {
        render(evalNum("1e20")) ==> "100000000000000000000"
        render(evalNum("1e21")) ==> "1e+21"
        // Expanding beyond it would spell 1e6000 as six thousand characters.
        render(evalNum("1e100")) ==> "1e+100"
      }

      test("round-trips preserve numeric equality") {
        val lits = Seq(
          "1",
          "1.0",
          "1e3",
          "1.25",
          "1e-30",
          "9007199254740993",
          "9223372036854775808",
          "0.1",
          "-0.0"
        )
        for (lit <- lits) {
          val v1 = evalNum(lit)
          val v2 = evalNum(render(v1))
          assertNumEq(v1, v2)
        }
      }
    }
  }

  private def interp: Interpreter = new Interpreter(
    Map(),
    Map(),
    DummyPath(),
    Importer.empty,
    parseCache = new DefaultParseCache
  )

  private def evalVal(s: String): Val =
    interp.evaluate(s, DummyPath("(memory)")) match {
      case Right(x) => x
      case Left(e)  => throw new Exception(Error.formatError(e))
    }

  private def evalNum(s: String): Val.Num = evalVal(s) match {
    case n: Val.Num => n
    case other      => throw new AssertionError(s"Expected number, got: $other")
  }

  private def eval(s: String): ujson.Value = TestUtils.eval(s)

  private def evalErr(s: String): String = TestUtils.evalErr(s)

  /** Render an expression to JSON text, bypassing `ujson.Value`'s Double-backed numbers. */
  private def renderExpr(s: String): String =
    interp.interpret0(s, DummyPath("(memory)"), new Renderer()) match {
      case Right(w)  => w.toString
      case Left(e)   => throw new Exception(e)
    }

  /** The canonical text of a number, i.e. what `std.toString` and every renderer agree on. */
  private def render(n: Val.Num): String = RenderUtils.renderNum(n)

  private def numKind(v: Val.Num): String = v match {
    case _: Val.Int64   => "int64"
    case _: Val.Float64 => "float64"
    case _: Val.Dec128  => "dec128"
  }

  private def assertKind(v: Val.Num, expected: String): Unit =
    assert(numKind(v) == expected, s"Expected $expected, got ${numKind(v)}: $v")

  private def assertNumEq(a: Val.Num, b: Val.Num): Unit =
    assert(NumberMath.compareTo(a, b) == 0, s"Expected equal: $a vs $b")

  private def indices(s: String): (Int, Int) = (
    s.indexOf('.'), {
      val lower = s.indexOf('e')
      if (lower >= 0) lower else s.indexOf('E')
    }
  )
}
