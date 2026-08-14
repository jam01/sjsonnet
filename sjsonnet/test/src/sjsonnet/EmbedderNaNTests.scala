package sjsonnet

import utest._

/**
 * `Val.Float64` rejects `NaN` at construction, the same as `Infinite` — the only way a `NaN`
 * could otherwise reach Jsonnet is an embedder handing one in directly (e.g. via
 * `ReadWriter[Double]`), since no pure-Jsonnet expression can produce one. That single choke
 * point means every consumer — arithmetic, unary minus, comparisons, manifestation — sees a
 * clean, uniform error instead of having to guard against `NaN` on its own.
 */
object EmbedderNaNTests extends TestSuite {
  private def variableResolve(name: String): Option[Expr] =
    if (name == "nan") Some(Val.Float64(new Position(null, 0), Double.NaN)) else None

  private val interpreter = new Interpreter(
    Map(),
    Map(),
    DummyPath(),
    Importer.empty,
    parseCache = new DefaultParseCache,
    variableResolver = variableResolve
  )

  private def checkFails(expr: String): Unit = {
    interpreter.interpret(expr, DummyPath("(memory)")) match {
      case Left(err) => assert(err.contains("Not a number"))
      case Right(v)  => throw new Exception(s"expected '$expr' to fail, got: $v")
    }
  }

  def tests: Tests = Tests {
    test("referencing the embedded value fails") {
      checkFails("nan")
    }

    test("unary minus fails instead of silently returning NaN") {
      checkFails("-nan")
    }

    test("arithmetic fails with a clean message instead of a leaked parser exception") {
      checkFails("1 + nan")
      checkFails("nan - 1")
      checkFails("nan * 2")
      checkFails("nan / 2")
    }

    test("std.max/min/abs/clamp fail") {
      checkFails("std.max(nan, 1)")
      checkFails("std.min(nan, 1)")
      checkFails("std.abs(nan)")
      checkFails("std.clamp(nan, 0, 1)")
    }

    test("manifestation fails instead of emitting invalid JSON") {
      checkFails("std.manifestJson(nan)")
      checkFails("std.toString(nan)")
    }
  }
}
