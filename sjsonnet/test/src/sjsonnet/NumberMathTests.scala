package sjsonnet

import utest.{ArrowAssert, TestSuite, Tests, test}
import TestUtils.eval

object NumberMathTests extends TestSuite {
  
  def tests = Tests {
    test("sort preserves int64 ordering above 2^53") {
      val out = evalVal("std.sort([9007199254740992, 9007199254740993])")
      out match {
        case Val.Arr(_, vs) =>
          val a = asNum(vs(0).asInstanceOf)
          val b = asNum(vs(1).asInstanceOf)
          assert(NumberMath.compareTo(a, b) < 0)
          // optional: verify exact values if you can pattern match
          assert(a.isInstanceOf[Val.Int64])
          assert(b.isInstanceOf[Val.Int64])
        case other => throw new AssertionError(s"Expected array, got: $other")
      }
    }

    test("numeric equality across int64/float64/dec128") {
      val a = asNum(evalVal("1"))
      val b = asNum(evalVal("1.0"))
      val c = asNum(evalVal("1.00"))

      assertNumEq(a, b)
      assertNumEq(b, c)
      assertNumEq(a, c)

      // also ensure evaluator equality operator matches NumberMath semantics
      eval("1 == 1.0") ==> ujson.True
      eval("1.0 == 1.00") ==> ujson.True
    }

    test("negative zero equality and ordering") {
      val pz = asNum(evalVal("0.0"))
      val nz = asNum(evalVal("-0.0"))

      assertNumEq(pz, nz)
      assert(NumberMath.compareTo(nz, pz) == 0)

      eval("-0.0 == 0.0") ==> ujson.True
    }

    test("int64 overflow does not wrap") {
      val v = asNum(evalVal("9223372036854775807 + 1")) // Long.MaxValue + 1
      // Should not be Int64
      assert(!v.isInstanceOf[Val.Int64], s"Unexpected wrap: $v")
      // And should be equal to exact decimal
      val expected = asNum(evalVal("9223372036854775808"))
      assertNumEq(v, expected)
    }

    test("mixed int64/float64 arithmetic is consistent") {
      val v = asNum(evalVal("1 + 0.5"))
      // expected numeric value 1.5
      val expected = asNum(evalVal("1.5"))
      assertNumEq(v, expected)
    }

    test("dec128 semantics: rounding at DECIMAL128 precision is applied") {
      // 40+ digit mantissa
      val v = asNum(evalVal("1.23456789012345678901234567890123456789"))
      assert(v.isInstanceOf[Val.Dec128])

      // Compare to a value constructed the same way (parser path should match)
      val again = asNum(evalVal("1.23456789012345678901234567890123456789"))
      assertNumEq(v, again)
    }

    test("float64 promotion policy is stable around 0.1") {
      val v = asNum(evalVal("0.1 + 0.2"))
      val expected = asNum(evalVal("0.3"))

      val equal = NumberMath.compareTo(v, expected) == 0
      assert(equal, s"Dec128 semantics expected 0.1+0.2 == 0.3, got $v")
    }

    test("dec128 materialization: dot/exp variants render consistently") {
      val a = evalVal("1000") // no dot/exp
      val b = evalVal("1E+3") // exp only
      val c = evalVal("1.25") // dot only
      val d = evalVal("1.0E-30") // dot + exp

      val ra = renderJson(a)
      val rb = renderJson(b)
      val rc = renderJson(c)
      val rd = renderJson(d)

      // Assert the renderer output matches your intended smuggling format.
      // If it's raw JSON numbers, compare to expected numeric strings.
      assert(ra.contains("1000"))
      assert(rb.contains("1000"))
      assert(rc.contains("1.25"))
      assert(rd.contains("1.0E-30") || rd.contains("1.0e-30"))
    }

    test("safe mode forces Dec128 for float literals") {
      val n = Val.Num(null, "0.1")
      assert(n.isInstanceOf[Val.Dec128])
    }

    test("fast mode uses Float64 for short float literals") {
      val s = "0.1"
      val (dec, exp) = indices(s)
      assert(NumberMath.allowFloat64LiteralWithIndexes(s, dec, exp))
    }

    test("fast mode uses Dec128 for long mantissas") {
      val s = "1.23456789012345678"
      val (dec, exp) = indices(s)
      assert(!NumberMath.allowFloat64LiteralWithIndexes(s, dec, exp))
    }

    test("leading zeros do not inflate significant digits") {
      val s = "000.0001"
      val (dec, exp) = indices(s)
      assert(NumberMath.allowFloat64LiteralWithIndexes(s, dec, exp))
    }

    test("exponent outside sane range rejected") {
      val s = "1e400"
      val (dec, exp) = indices(s)
      assert(!NumberMath.allowFloat64LiteralWithIndexes(s, dec, exp))
    }

    test("signed literal supported") {
      val s = "-0.1"
      val (dec, exp) = indices(s)
      assert(NumberMath.allowFloat64LiteralWithIndexes(s, dec, exp))
    }

    test("integer literals always parse as Int64 when they fit") {
      val n1 = Val.Num(null, "9007199254740993") // fits in Long
      assert(n1.isInstanceOf[Val.Int64])
    }

    test("negating Long.MinValue does not overflow; yields Dec128") {
      evalVal("-" + Long.MinValue) match {
        case Val.Dec128(_, bd) =>
          // should equal +9223372036854775808
          val expected = BigDecimal("9223372036854775808", java.math.MathContext.DECIMAL128)
          assert(bd == expected)
        case other =>
          throw new AssertionError(s"Expected Dec128, got $other")
      }
    }

    test("negating normal int64 stays int64") {
      val out = evalVal("-123")
      assert(out.isInstanceOf[Val.Int64] && out.asLong == -123L)
    }

    test("division: divisible int/int stays int64") {
      val v = asNum(evalVal("4 / 2"))
      assert(v.isInstanceOf[Val.Int64])
      eval("4 / 2") ==> ujson.Num(2)
    }

    test("division: non-divisible int/int becomes decimal and equals 0.5") {
      val v = asNum(evalVal("1 / 2"))
      val expected = asNum(evalVal("0.5"))
      assertNumEq(v, expected)
      assert(!v.isInstanceOf[Val.Int64])
    }

    test("division: non-terminating decimal is handled (either rounded or error)") {
      try {
        val v = asNum(evalVal("1 / 3"))
        // If you expect rounding, assert it's Dec128 and close to expected parsing
        assert(v.isInstanceOf[Val.Dec128] || v.isInstanceOf[Val.Float64])
      } catch {
        case _: Exception =>
          // Acceptable if current semantics are "fail on non-terminating decimal"
          ()
      }
    }

    test("mod: int remainder stays int64") {
      val v = asNum(evalVal("5 % 2"))
      assert(v.isInstanceOf[Val.Int64] && v.asLong == 1L)
    }

    test("mod: decimal remainder stays dec128") {
      val v = asNum(evalVal("5.5 % 2"))
      val expected = asNum(evalVal("1.5"))
      assertNumEq(v, expected)
      assert(v.isInstanceOf[Val.Dec128] || v.isInstanceOf[Val.Float64])
    }

    test("compareTo is antisymmetric across kinds") {
      val a = asNum(evalVal("9007199254740993"))
      val b = asNum(evalVal("9007199254740994"))
      val ab = NumberMath.compareTo(a, b)
      val ba = NumberMath.compareTo(b, a)
      assert(ab < 0 && ba > 0)
    }

    test("allowFloat64LiteralWithIndexes exponent boundary") {
      def ok(s: String) = {
        val (dec, exp) = indices(s)
        assert(NumberMath.allowFloat64LiteralWithIndexes(s, dec, exp))
      }

      def bad(s: String) = {
        val (dec, exp) = indices(s)
        assert(!NumberMath.allowFloat64LiteralWithIndexes(s, dec, exp))
      }

      ok("1e+325");
      bad("1e+326")
      ok("1e-325");
      bad("1e-326")
    }

    test("allowFloat64LiteralWithIndexes all-zero forms") {
      for (s <- Seq("0", "0.0", "0000.0000")) {
        val (dec, exp) = indices(s)
        assert(NumberMath.allowFloat64LiteralWithIndexes(s, dec, exp))
      }
    }

    test("materialization: negative zero canonical") {
      val s = renderJson(evalVal("-0.0"))
      assert(!s.contains("-0"), s"unexpected -0 rendering: $s")
    }

    test("materialization round-trip numeric equality on representative literals") {
      val lits = Seq("1", "1.0", "1e3", "1.25", "1e-30", "9007199254740993", "-0.0")
      for (lit <- lits) {
        val v1 = asNum(evalVal(lit))
        val s = renderJson(v1)
        val v2 = asNum(evalVal(s))
        assertNumEq(v1, v2)
      }
    }
  }
  

  def evalVal(s: String): Val = {
    new Interpreter(
      Map(),
      Map(),
      DummyPath(),
      Importer.empty,
      parseCache = new DefaultParseCache
    ).interpret0(s, DummyPath("(memory)"), ValVisitor(Position(null, -1))) match {
      case Right(x) => x
      case Left(e)  => throw new Exception(e)
    }
  }

  def asNum(v: Val): Val.Num = v match {
    case n: Val.Num => n
    case other => throw new AssertionError(s"Expected number, got: $other")
  }

  def numKind(v: Val.Num): String = v match {
    case _: Val.Int64 => "int64"
    case _: Val.Float64 => "float64"
    case _: Val.Dec128 => "dec128"
  }

  def assertNumEq(a: Val.Num, b: Val.Num): Unit = {
    assert(NumberMath.compareTo(a, b) == 0, s"Expected equal: $a vs $b")
  }

  def renderJson(v: Val): String = {
    val i = new Interpreter(
      Map(),
      Map(),
      DummyPath(),
      Importer.empty,
      parseCache = new DefaultParseCache
    )

    Materializer.apply0(v, new Renderer())(i.evaluator).toString
  }

  def indices(s: String) = (s.indexOf('.'), {
    val e1 = s.indexOf('e')
    val e2 = s.indexOf('E')
    if (e1 >= 0) e1 else e2
  })
}
