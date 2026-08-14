// Rendered-output coverage for the exact numeric core (see madr-better-nums.md).
//
// This file exists because `BaseFileTests.check` compares `Interpreter.interpret`'s ujson.Value
// against the golden, and ujson stores every number as a Double. Any test whose *result* is a
// number outside binary64's range or precision therefore cannot be checked by that harness —
// error.overflow, error.overflow2, div4, inf_* and parseint_large_precision are skip-listed in
// FileTests.scala for exactly that reason.
//
// Everything below stringifies the number inside Jsonnet and asserts on the string, so the file
// evaluates to a boolean and survives the narrowing intact. std.toString and every renderer share
// RenderUtils.renderNum, so these assertions pin what the CLI actually prints.

// --- Decimal literals are exact, not binary64 ------------------------------------------------
std.assertEqual(std.toString(0.1 + 0.2), "0.3") &&
std.assertEqual(0.1 + 0.2 == 0.3, true) &&
std.assertEqual(std.toString(0.1 * 3), "0.3") &&
std.assertEqual(std.toString(1.1 - 1.0), "0.1") &&
// A decimal that happens to be whole renders without its trailing scale.
std.assertEqual(std.toString(1.5 + 0.5), "2") &&
// Rounded at DECIMAL128's 34 significant digits.
std.assertEqual(std.toString(1 / 3), "0.3333333333333333333333333333333333") &&

// --- Values outside binary64 range are constructible and render exactly ----------------------
// Upstream raises Overflow for all four of these; here they are ordinary Dec128 values.
std.assertEqual(std.toString(1e308 + 1e308), "2e+308") &&
std.assertEqual(std.toString(-1e308 - 1e308), "-2e+308") &&
std.assertEqual(std.toString(1e300 * 1000000000), "1e+309") &&
std.assertEqual(std.toString(1 / (1e-160) / (1e-160)), "1e+320") &&
std.assertEqual(std.toString(1e309), "1e+309") &&
// Small magnitudes too: go-jsonnet's binary64 artifact here is 9.9999999999999991e-31.
std.assertEqual(std.toString(1 / 1e30), "1e-30") &&
// An exponent past Int range still parses and renders: normalizeScientificString used to
// narrow it through Integer.parseInt and crash.
std.assertEqual(std.toString(1e2147483648), "1e+2147483648") &&

// --- Integers are exact past 2^53 ------------------------------------------------------------
std.assertEqual(std.toString(9007199254740993), "9007199254740993") &&
std.assertEqual(std.toString(9223372036854775807), "9223372036854775807") &&
// Past Long range an integer literal widens to Dec128 rather than rounding to a double; this
// used to print 9223372036854776000.
std.assertEqual(std.toString(9223372036854775808), "9223372036854775808") &&
// Int64 arithmetic must widen rather than wrap.
std.assertEqual(std.toString(9223372036854775807 + 1), "9223372036854775808") &&
// Distinct values that share a Double stay distinct.
std.assertEqual(9007199254740993 == 9007199254740994, false) &&
std.assertEqual(std.primitiveEquals(9007199254740993, 9007199254740994), false) &&
std.assertEqual(std.sort([9007199254740994, 9007199254740993]),
                [9007199254740993, 9007199254740994]) &&
std.assertEqual(std.length(std.setUnion([9007199254740993], [9007199254740994])), 2) &&

// --- parseInt / parseHex / parseOctal are exact ----------------------------------------------
// The exactness half of parseint_large_precision, which asserts the old rounding and is
// skip-listed because an Int64 past 2^53 comes back from `interpret` as a ujson Str.
std.assertEqual(std.toString(std.parseInt("12345678901234567")), "12345678901234567") &&
std.assertEqual(std.toString(std.parseInt("9007199254740993")), "9007199254740993") &&
std.assertEqual(std.toString(std.parseInt("999999999999999999")), "999999999999999999") &&
std.assertEqual(std.toString(std.parseInt("-999999999999999999")), "-999999999999999999") &&
// Beyond Long range, so Dec128 — and exact, where the old code gave …616.
std.assertEqual(std.toString(std.parseHex("FFFFFFFFFFFFFFFF")), "18446744073709551615") &&
std.assertEqual(std.toString(std.parseOctal("1000000000000000000000")), "9223372036854775808") &&

// --- Negative zero survives every path -------------------------------------------------------
// Neither Long nor BigDecimal has a signed zero, so -0 must be carried as a Float64. Upstream
// deliberately fixed bare `-0` rendering (#926) and this is the regression guard for it.
std.assertEqual(std.toString(-0), "-0") &&
std.assertEqual(std.toString(0 * -1), "-0") &&
std.assertEqual(std.toString(-0 + -0), "-0") &&
std.assertEqual(std.toString(0 - 0), "0") &&
std.assertEqual(std.toString(-6 % 3), "-0") &&
std.assertEqual(std.manifestJsonMinified({ a: -0, b: 0 }), '{"a":-0,"b":0}') &&
std.assertEqual(std.toString(std.parseJson("-0")), "-0") &&
// Still equal to +0, as IEEE-754 requires.
std.assertEqual(-0 == 0, true) &&
std.assertEqual(std.primitiveEquals(-0, 0), true) &&

// --- Int64 / Int64 -----------------------------------------------------------------------------
// An inexact quotient of two Int64s is a Dec128. NumberMath computes the terminating ones in Long
// arithmetic instead of BigDecimal.divide, so these pin that the two agree on the printed digits —
// which means on value *and* scale, since a stray trailing zero would show.
std.assertEqual(std.toString(3 / 2), "1.5") &&
std.assertEqual(std.toString(10 / 4), "2.5") &&
std.assertEqual(std.toString(123 / 40), "3.075") &&
std.assertEqual(std.toString(1 / 1024), "0.0009765625") &&
std.assertEqual(std.toString(1 / 3125), "0.00032") &&
std.assertEqual(std.toString(1 / 100000), "0.00001") &&
// Sign lives on the quotient, not on the scaling.
std.assertEqual(std.toString(-7 / 2), "-3.5") &&
std.assertEqual(std.toString(7 / -2), "-3.5") &&
std.assertEqual(std.toString(-7 / -2), "3.5") &&
// Scaling the numerator by 10 would overflow a Long, so these fall back to BigDecimal.divide.
std.assertEqual(std.toString(999999999999999999 / 2), "499999999999999999.5") &&
std.assertEqual(std.toString(1234567890123456789 / 4), "308641972530864197.25") &&
std.assertEqual(std.toString(-9223372036854775807 / 2), "-4611686018427387903.5") &&
// Terminating, but far deeper than a Long reaches: 2^-51 fits DECIMAL128's 34 digits exactly,
// 2^-53 does not and rounds.
std.assertEqual(std.toString(1 / 2251799813685248), "4.440892098500626161694526672363281e-16") &&
std.assertEqual(std.toString(1 / 9007199254740992), "1.11022302462515654042363166809082e-16") &&
// Non-terminating quotients round at 34 significant digits, as 1 / 3 does above.
std.assertEqual(std.toString(2 / 7), "0.2857142857142857142857142857142857") &&
std.assertEqual(std.toString(1 / 6), "0.1666666666666666666666666666666667") &&
// A quotient that divides evenly stays an Int64 and never reaches any of the above.
std.assertEqual(std.toString(100 / 4), "25") &&
// Long.MinValue / -1 divides evenly too, but the Long quotient itself overflows — this must
// widen to Dec128 rather than silently wrapping back to Long.MinValue.
std.assertEqual(std.toString((-9223372036854775807 - 1) / -1), "9223372036854775808") &&
// Exactness survives into the next operation.
std.assertEqual(std.toString(1 / 2 * 2), "1") &&
std.assertEqual(std.toString((1 / 4) + (1 / 4)), "0.5") &&
std.assertEqual(3 / 2 == 1.5, true) &&

// --- Modulo -----------------------------------------------------------------------------------
// Decimally correct: IEEE fmod gives 0.09999999999999998 here.
std.assertEqual(std.toString(0.3 % 0.1), "0") &&
// BigDecimal.remainder raises "Division impossible" once the implied quotient needs more than 34
// digits, even though the remainder itself is trivially representable.
std.assertEqual(std.toString(1e40 % 3), "1") &&
std.assertEqual(std.toString(1e400 % 3), "1") &&
std.assertEqual(std.toString(9223372036854775807 % 1000), "807") &&

// --- Every number-to-string conversion agrees --------------------------------------------------
// std.toString, `%s`, `%(key)s` and `+` concatenation all share RenderUtils.renderNum, so none of
// them can put a different number in the output string than the others. `+` and the `%(key)s`
// fast path both used to narrow through asDouble and spelled the first value below …776000.
// `%(key)s` is checked twice per value because a repeated label takes a separate cached branch.
local agree(n, expected) =
  std.assertEqual(std.toString(n), expected) &&
  std.assertEqual('%s' % n, expected) &&
  std.assertEqual('%(n)s' % { n: n }, expected) &&
  std.assertEqual('%(n)s|%(n)s' % { n: n }, expected + '|' + expected) &&
  std.assertEqual('' + n, expected) &&
  std.assertEqual(n + '', expected);
agree(9223372036854775807, "9223372036854775807") &&
agree(1e21, "1e+21") &&
agree(1e400, "1e+400") &&
agree(0.1 + 0.2, "0.3") &&
agree(-0, "-0") &&

// --- Integer format conversions are exact; float ones are not ---------------------------------
// %d %i %u %o %x %X are *integer* conversions — rounding is not their defined behaviour the way
// it is for %e %f %g, and Python (whose %-formatting these follow) is arbitrary precision here.
// Narrowing them through asDouble made '%x' % 9223372036854775807 spell 2^63, a different number.
std.assertEqual('%d' % 9223372036854775807, "9223372036854775807") &&
std.assertEqual('%i' % 9223372036854775807, "9223372036854775807") &&
std.assertEqual('%u' % 9223372036854775807, "9223372036854775807") &&
std.assertEqual('%x' % 9223372036854775807, "7fffffffffffffff") &&
std.assertEqual('%X' % 9223372036854775807, "7FFFFFFFFFFFFFFF") &&
std.assertEqual('%o' % 9223372036854775807, "777777777777777777777") &&
std.assertEqual('%d' % 9007199254740993, "9007199254740993") &&
// Past Long range, and past what any double could hold.
std.assertEqual('%d' % 123456789012345678901234567890, "123456789012345678901234567890") &&
std.assertEqual('%d' % -9223372036854775808, "-9223372036854775808") &&
// Truncation toward zero, matching Python's '%d' % -3.7 == '-3'.
std.assertEqual('%d' % 3.7, "3") &&
std.assertEqual('%d' % -3.7, "-3") &&
// Flags and padding still apply on top of the exact digits.
std.assertEqual('%05d' % 42, "00042") &&
std.assertEqual('%#x' % 255, "0xff") &&
// The floating-point conversions deliberately still narrow to binary64.
std.assertEqual('%e' % 9223372036854775807, "9.223372e+18") &&
std.assertEqual('%.0f' % 1e30, "1000000000000000019884624838656") &&
// ...while the integer conversion of that same literal is exactly 10^30.
std.assertEqual('%d' % 1e30, "1000000000000000000000000000000") &&

// --- std.* keeps upstream's Double semantics, on purpose --------------------------------------
// Per madr-better-nums.md, "Scope: the language core is exact; the standard library is not". The
// operators are exact; std functions narrow. This asymmetry is the decision, not an oversight —
// pinned here so a future change to it is deliberate rather than accidental. Exactness beyond
// what std offers is xtr's job.
std.assertEqual(std.toString(std.sum([0.1, 0.2])), "0.30000000000000004") &&
std.assertEqual(std.toString(0.1 + 0.2), "0.3") &&
// A std result is inexact, and exact arithmetic over it stays precise-looking without being
// upstream-identical. Both of these are the same phenomenon, not two separate quirks.
std.assertEqual(std.toString(std.sqrt(2) * std.sqrt(2)), "2.00000000000000014481069235364401") &&
std.assertEqual(std.toString(std.sqrt(2) * 2), "2.8284271247461902") &&
// Exactness wins unconditionally, so the same quantity has one spelling however it is reached.
std.assertEqual(std.sqrt(2) + std.sqrt(2), std.sqrt(2) * 2) &&
std.assertEqual(std.sqrt(2) * 2, std.sqrt(2) * 2.0) &&

// --- ...except where a std function would answer with a number that was never an input --------
// Narrowing a value the function is supposed to leave alone is not "std is inexact", it is a
// no-op being destructive. 2^53+1 is exact as an Int64 and unrepresentable as a Double.
std.assertEqual(std.toString(std.max(9007199254740993, 1)), "9007199254740993") &&
std.assertEqual(std.toString(std.min(9007199254740993, 9007199254740994)), "9007199254740993") &&
std.assertEqual(std.toString(std.abs(9007199254740993)), "9007199254740993") &&
std.assertEqual(std.toString(std.floor(9007199254740993)), "9007199254740993") &&
std.assertEqual(std.toString(std.ceil(9007199254740993)), "9007199254740993") &&
std.assertEqual(std.toString(std.round(9007199254740993)), "9007199254740993") &&
std.assertEqual(std.toString(std.clamp(9007199254740993, 0, 9999999999999999999)), "9007199254740993") &&
// Whole Dec128 values are left alone too, not just Int64.
std.assertEqual(std.toString(std.floor(1234567890123456789012345678901234)),
                "1234567890123456789012345678901234") &&
// Rounding a value that genuinely needs it still goes through the Double path.
std.assertEqual(std.toString(std.floor(2.7)), "2") &&
std.assertEqual(std.toString(std.ceil(2.1)), "3") &&
std.assertEqual(std.toString(std.round(2.5)), "3") &&
std.assertEqual(std.toString(std.abs(-3)), "3") &&
std.assertEqual(std.toString(std.abs(-0)), "0") &&
// std.sum genuinely computes, so it is left as upstream.
std.assertEqual(std.toString(std.sum([9007199254740993, 0])), "9007199254740992") &&

true
