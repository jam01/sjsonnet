package sjsonnet

import utest.*

object FileTests extends BaseFileTests {

  /**
   * Tests whose real output this harness structurally cannot express.
   *
   * `check` compares `Interpreter.interpret`'s `ujson.Value` against `ujson.read(golden)`, and
   * `ujson.Value` stores every number as a `Double`. Since the numeric rework the language core is
   * exact, so these files evaluate to values outside binary64's range or precision — the renderers
   * (and therefore the CLI) print them correctly, but `interpret` narrows them to `Infinity` or to
   * a rounded double on the way into the comparison, and a golden simply cannot hold the true
   * value.
   *
   * Their goldens are deliberately left at upstream's text so a sync drops in clean; the decimal
   * semantics they used to pin are covered by rendered-output assertions in
   * `new_test_suite/decimal_semantics.jsonnet`, which yields a boolean and so survives the
   * narrowing. See `madr-better-nums.md` and the "Phase 3 — DONE" section of
   * `NUMBERS_REWORK_PLAN.md`.
   */
  private val ujsonNarrowingSkippedTests: Set[String] = Set(
    // 1e309 is a valid Dec128 now, not a parse error; result is outside double range.
    "error.overflow.jsonnet",
    "error.overflow2.jsonnet",
    // Arithmetic no longer overflows at binary64's boundaries.
    "error.arithmetic_overflow_addition.jsonnet",
    "error.arithmetic_overflow_multiplication.jsonnet",
    "error.arithmetic_overflow_subtraction.jsonnet",
    // parseInt/parseHex/parseOctal are exact now, so results past 2^53 come back as a ujson Str.
    "parseint_large_precision.jsonnet"
  )

  private val goUjsonNarrowingSkippedTests: Set[String] = Set(
    // Results outside binary64 range; see ujsonNarrowingSkippedTests.
    "div4.jsonnet",
    "inf_min_number.jsonnet",
    "inf_mul_number.jsonnet",
    "inf_sum_number.jsonnet"
  )

  val testDataSkippedTests: Set[String] = ujsonNarrowingSkippedTests ++
    (if (isScalaNative)
      Set(
        // These tests are skipped in Scala Native because we can't catch the stack overflow and recover.
        "error.obj_recursive_manifest.jsonnet",
        "error.recursive_object_non_term.jsonnet",
        "error.recursive_import.jsonnet",
        "error.recursive_function_nonterm.jsonnet",
        "error.function_infinite_default.jsonnet",
        "error.obj_recursive.jsonnet",
        // YAML merge key support is JVM-only (SnakeYAML specific)
        "parseyaml_merge_keys.jsonnet",
        // Block scalar clip chomping fix is JVM-only (SnakeYAML specific)
        "parseyaml_block_scalar_chomping.jsonnet"
      )
    else Set.empty[String])

  val goTestDataSkippedTests: Set[String] = goUjsonNarrowingSkippedTests ++ Set(
    // We support base64 of unicode strings
    "builtinBase64_string_high_codepoint.jsonnet"
  )

  val tests: Tests = Tests {
    test("test_suite") - {
      val t = os
        .list(testSuiteRoot / "test_suite")
        .filter(f => f.ext == "jsonnet")
        .filter(f => !testDataSkippedTests.contains(f.last))
      assert(t.nonEmpty)
      t.foreach { file =>
        check(file, "test_suite")
      }
      printSummaryAndAssert()
    }
    test("go_test_suite") - {
      val t = os
        .list(testSuiteRoot / "go_test_suite")
        .filter(f => f.ext == "jsonnet")
        .filter(f => !goTestDataSkippedTests.contains(f.last))
      assert(t.nonEmpty)
      t.foreach { file =>
        check(file, "go_test_suite")
      }
      printSummaryAndAssert()
    }
    test("new_test_suite") - {
      val t = os
        .list(testSuiteRoot / "new_test_suite")
        .filter(f => f.ext == "jsonnet" && !f.last.contains("-js"))
        .filter(f => !testDataSkippedTests.contains(f.last))
      assert(t.nonEmpty)
      t.foreach { file =>
        check(file, "new_test_suite")
      }
      printSummaryAndAssert()
    }
  }
}
