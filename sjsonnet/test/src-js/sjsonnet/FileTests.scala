package sjsonnet

import utest._

object FileTests extends BaseFileTests {

  /**
   * Tests whose real output this harness structurally cannot express — see the same-named set in
   * `src-jvm-native/sjsonnet/FileTests.scala` for the full rationale. In short: `check` compares a
   * `ujson.Value`, which stores every number as a `Double`, and since the numeric rework these
   * files evaluate to values outside binary64's range or precision.
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

  val skippedTests: Set[String] = ujsonNarrowingSkippedTests ++ Set(
    // Stack size issues with the JS runner
    "recursive_function.jsonnet",
    "error.array_recursive_manifest.jsonnet",
    "error.obj_recursive_manifest.jsonnet",
    "error.recursive_object_non_term.jsonnet",
    "error.recursive_import.jsonnet",
    "error.recursive_function_nonterm.jsonnet",
    "error.function_infinite_default.jsonnet",
    "error.obj_recursive.jsonnet",
    // Merge keys fix is JVM-only (SnakeYAML specific)
    "parseyaml_merge_keys.jsonnet",
    // Block scalar clip chomping fix is JVM-only (SnakeYAML specific)
    "parseyaml_block_scalar_chomping.jsonnet"
  )

  val goTestDataSkippedTests: Set[String] = Set(
    // Results outside binary64 range; see ujsonNarrowingSkippedTests.
    "div4.jsonnet",
    "inf_min_number.jsonnet",
    "inf_mul_number.jsonnet",
    "inf_sum_number.jsonnet",
    // We support base64 of unicode strings
    "builtinBase64_string_high_codepoint.jsonnet",
    "builtinSha1.jsonnet",
    "builtinSha256.jsonnet",
    "builtinSha3.jsonnet",
    "builtinSha512.jsonnet",
    "std.md5.jsonnet",
    "std.md5_2.jsonnet",
    "std.md5_3.jsonnet",
    "std.md5_4.jsonnet",
    "std.md5_5.jsonnet",
    "std.md5_6.jsonnet"
  )

  val tests: Tests = Tests {
    test("test_suite") - {
      val t = TestResources_test_suite.files.keys.toSeq
        .filter(f => f.matches("[^/]+\\.jsonnet"))
        .filter(f => !skippedTests.contains(f))
        .sorted
      assert(t.nonEmpty)
      t.foreach { file =>
        check(TestResources_test_suite.files, file, "test_suite")
      }
      printSummaryAndAssert()
    }

    test("go_test_suite") - {
      val t = TestResources_go_test_suite.files.keys.toSeq
        .filter(f => f.matches("[^/]+\\.jsonnet"))
        .filter(f => !goTestDataSkippedTests.contains(f))
        .sorted
      assert(t.nonEmpty)
      t.foreach { file =>
        check(TestResources_go_test_suite.files, file, "go_test_suite")
      }
      printSummaryAndAssert()
    }

    test("new_test_suite") - {
      val t = TestResources_new_test_suite.files.keys.toSeq
        .filter(f =>
          f.matches("[^/]+-js\\.jsonnet") || (f.matches("[^/]+\\.jsonnet") && !f.contains("-jvm"))
        )
        .filter(f => !skippedTests.contains(f))
        .sorted
      assert(t.nonEmpty)
      t.foreach { file =>
        check(TestResources_new_test_suite.files, file, "new_test_suite")
      }
      printSummaryAndAssert()
    }
  }
}
