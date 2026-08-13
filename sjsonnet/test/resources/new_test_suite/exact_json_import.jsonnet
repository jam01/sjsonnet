// A JSON import must carry numbers as exactly as a jsonnet literal or std.parseJson does.
// The fast path in CachedResolver.JsonImportVisitor narrowed every number through Double, which
// made `import "x.json"` — the way a transformation usually reads its data — the one ingest path
// that could not hold an exact value.
local imported = import "exact_json_import_data.json";
local parsed = std.parseJson(importstr "exact_json_import_data.json");

// Every number survives the import unrounded.
std.assertEqual(std.toString(imported.seventeen_digits), "0.12345678901234567") &&
std.assertEqual(std.toString(imported.past_2_53), "9007199254740993") &&
std.assertEqual(std.toString(imported.thirty_four_digits), "123456789012345678901234567890123.5") &&
// 35 significant digits: Dec128 rounds at 34, and the import agrees with every other path on that.
std.assertEqual(std.toString(imported.past_dec128_precision), "1234567890123456789012345678901234") &&
std.assertEqual(std.toString(imported.past_long), "9223372036854775808") &&
std.assertEqual(std.toString(imported.plain), "1.5") &&
// Magnitudes outside binary64 used to make the fast path bail to the jsonnet parser; it now
// carries them itself, and still exactly.
std.assertEqual(std.toString(imported.past_binary64), "1e+400") &&
// -0 survives too, the one value only Float64 can carry.
std.assertEqual(std.toString(imported.negative_zero), "-0") &&

// The three ingest paths agree: import, std.parseJson, and a literal in jsonnet source.
std.assertEqual(imported, parsed) &&
std.assertEqual(imported.seventeen_digits, 0.12345678901234567) &&
std.assertEqual(imported.past_2_53, 9007199254740993) &&
std.assertEqual(imported.past_2_53 == 9007199254740992, false) &&

// Exactness reaches arithmetic, not just rendering.
std.assertEqual(std.toString(imported.past_2_53 + 1), "9007199254740994") &&
std.assertEqual(std.toString(imported.plain + 0.1), "1.6") &&

true
