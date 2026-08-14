// Regression test: comparisons against a near-integer Dec128 literal inside a pure
// comprehension over a std.range() array must agree with the same comparison over an
// equivalent non-range array, and with the same comparison evaluated standalone.
local nearOne = 1.00000000000000000000001;  // exact Dec128, strictly greater than 1

local fromRange = [x < nearOne for x in std.range(0, 3)];
local fromArray = [x < nearOne for x in [0, 1, 2, 3]];

local fromRangeEq = [x == 1.0 for x in std.range(0, 3)];
local fromArrayEq = [x == 1.0 for x in [0, 1, 2, 3]];

std.assertEqual(fromRange, fromArray) &&
std.assertEqual(fromRange, [true, true, false, false]) &&
std.assertEqual(1 < nearOne, true) &&
std.assertEqual(fromRangeEq, fromArrayEq) &&
std.assertEqual(fromRangeEq, [false, true, false, false]) &&
true
