package sjsonnet

import utest.*

object FileTests extends BaseFileTests {
  val testDataSkippedTests: Set[String] =
    if (isScalaNative)
      Set(
        // These tests are skipped in Scala Native because we can't catch the stack overflow and recover.
        "error.obj_recursive_manifest.jsonnet",
        "error.recursive_object_non_term.jsonnet",
        "error.recursive_import.jsonnet",
        "error.recursive_function_nonterm.jsonnet",
        "error.function_infinite_default.jsonnet",
        "error.obj_recursive.jsonnet",

        "error.overflow.jsonnet", // no overflow, infinite number in ujson
        "error.overflow2.jsonnet", // no overflow, infinite number in ujson
      )
    else Set(
      "error.overflow.jsonnet", // no overflow, infinite number in ujson
      "error.overflow2.jsonnet", // no overflow, infinite number in ujson
    )

  val goTestDataSkippedTests: Set[String] = Set(
    // We support base64 of unicode strings
    "builtinBase64_string_high_codepoint.jsonnet",

    "div4.jsonnet", // infinite number in ujson
    "inf_min_number.jsonnet", // infinite number in ujson
    "inf_mul_number.jsonnet", // infinite number in ujson
    "inf_sum_number.jsonnet", // infinite number in ujson
    "bitwise_or9.jsonnet", // ujson truncates int64 at 2^53
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
    }
    test("new_test_suite") - {
      val t = os
        .list(testSuiteRoot / "new_test_suite")
        .filter(f => f.ext == "jsonnet" && !f.last.contains("-js"))
      assert(t.nonEmpty)
      t.foreach { file =>
        check(file, "new_test_suite")
      }
    }
  }
}
