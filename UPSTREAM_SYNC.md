# Upstream Sync Playbook

Audience: an AI agent with shell access to this repo, executing an upgrade.
Not a narrative — follow the numbered procedure literally. Ask the user at
any point marked "ASK", don't guess.

## Vocabulary

- **upstream** — the `upstream` git remote (`databricks/sjsonnet`).
- **origin** — the `origin` git remote (`jam01/sjsonnet`, this fork).
- **integration branch** — a branch named `sjsonnet-<TAG>-x`, cut from
  upstream tag `<TAG>`, carrying every fork patch on top. e.g.
  `sjsonnet-0.6.0-x` was cut from upstream tag `0.6.0`.
- **patch** — one commit (occasionally 2-3 in a row) implementing one fork
  feature/fix, absent from upstream. Authored by `jam01`.
- **release-coordinates commit** — identify by CONTENT, not position: the
  commit that touches only `build.mill`'s `pomSettings` (organization, url,
  versionControl, developers) to set this fork's own publish identity. It is
  usually near the end of an integration branch but is not guaranteed to be
  literally last — e.g. on `sjsonnet-0.6.0-x` it sat in the middle, followed
  by two more patch commits. Cherry-picked like any other patch — see
  step 5.
- **OLD_TAG / NEW_TAG** — the upstream tag the current and target
  integration branches are/will be cut from.

## Preconditions

```
git fetch upstream --tags
git fetch origin
```

Find the current integration branch:
```
git branch -a --sort=-committerdate | grep -o 'sjsonnet-[0-9.]*-x' | head -1
```
`OLD_TAG` is embedded in its name.

`NEW_TAG` — ASK the user which upstream tag to target. Do not default to
the newest tag silently: `upstream/master` and other upstream branches (e.g.
`branch-0.7.0`) can be ahead of the latest tag, and the user may want a
specific stable point, not tip-of-tree. List candidates for them:
```
git tag -l --sort=-v:refname | head -10
```

A note on the compile/test commands used throughout this procedure: the CI
wildcard form (`./mill _.jvm[_].__.compile`) is what `.github/workflows/`
actually runs and covers every target (jvm/js/native × every Scala
version) — it's also slow enough to reliably blow past a short foreground
timeout, so run it in the background. For a fast per-patch check, target
just the JVM build at the project's primary Scala version: read
`val scalaVersions` near the top of `build.mill` and use its head, e.g.
`./mill sjsonnet.jvm[3.3.8].compile` — don't guess or reuse a version
number from a prior cycle, it does drift.

## Procedure

### 1. Build the patch list

```
git log --oneline --reverse <OLD_TAG>..sjsonnet-<OLD_TAG>-x
```

Filter this list before using it:

- **Drop** the release-coordinates commit (identify by content — touches
  only `build.mill` pomSettings; don't assume it's positionally last, see
  Vocabulary). Handled separately in step 6.
- **Drop / flatten** any merge commits
  (`git log --merges <OLD_TAG>..sjsonnet-<OLD_TAG>-x`). Cherry-pick-only is
  the standing pattern; a past merge-based cycle (`rebase-045`,
  `sjsonnet-0.4.5-x`) was a one-off deviation, not precedent to repeat. If a
  merge commit is found in range, ASK the user how to treat it.
- **Keep** everything else, in original order. Two commits with related
  subjects (e.g. two "better numbers" commits) are one logical patch split
  in two — replay both, in order. Don't pre-emptively squash.
- Log the final ordered sha list before proceeding — it's the record of
  what this cycle replays, and an input to step 5.

### 2. Cut the new integration branch

```
git checkout -b sjsonnet-<NEW_TAG>-x <NEW_TAG>
```

### 3. Replay patches, oldest first

For each sha from step 1:

```
git cherry-pick <sha>
```

- **Clean apply** → next commit.
- **Conflict** → do not resolve by blindly picking "ours" or "theirs".
  Read what upstream actually changed at those lines since `OLD_TAG`:
  ```
  git log OLD_TAG..NEW_TAG -- <conflicting file>
  ```
  Understand *why* upstream changed it, then hand-merge so the patch's
  original behavior survives against the new code shape. If the conflict
  was non-trivial, run that module's tests before moving to the next patch.
- **Patch looks obsolete** (upstream appears to have implemented equivalent
  behavior independently — check `CHANGELOG.md` and commit subjects in
  `OLD_TAG..NEW_TAG` for related keywords) → ASK the user whether to drop
  the patch instead of forcing it in.
- **Patch's diff surface is large** (rough guide: conflicts touch
  significantly more files than a quick read of the original diff suggests,
  or the original patch itself spans >300 lines / a dozen+ files) → don't
  attempt line-by-line conflict resolution. Abort the cherry-pick
  (`git cherry-pick --abort`) and dispatch a proper investigation first:
  for every file the patch touches, compare what the patch changes there
  against everything upstream changed there in `OLD_TAG..NEW_TAG`
  (`git log --oneline OLD_TAG..NEW_TAG -- <file>`), and classify each as
  orthogonal / needs-reconciliation / redundant / conflicting-design before
  writing a single line. A patch that touches core evaluation or type
  representation is exactly the kind upstream is also likely to have
  independently reworked — resolving conflicts there without that map
  produces code that compiles but silently drops the patch's actual intent.
  ASK the user before deciding whether to push through anyway, rework by
  hand, or defer.
- Record old-sha → new-sha as you go; needed for step 4.
- **After each successful replay (clean or resolved), compile and run the
  full test suite before moving to the next patch** — not just at the end:
  ```
  ./mill _.jvm[_].__.compile
  ./mill _.jvm[_].__.test
  ```
  `git cherry-pick` only flags conflicts *near existing diff context*. A
  signature/arity change (e.g. a case class gaining a field) can silently
  break pattern matches or call sites elsewhere in the codebase that never
  showed up as a conflict — these only surface at compile time, and if you
  wait until the end to build, the fix lands in the wrong commit (or gets
  swept into a later, unrelated one — see the staging note in step 5) and
  the history stops being a valid changelog. Fix stray breaks immediately,
  then `git add <path> && git commit --amend --no-edit` onto the patch that
  actually caused them.
- **Staging**: after resolving conflicts, stage explicitly
  (`git add <path>...`) or use `git add -u` (tracked files only) — not
  `git add -A`. The working tree may have pre-existing untracked scratch
  files (IDE project files, WIP notes) that have nothing to do with the
  patch; `-A` will silently commit them. If it happens anyway, catch it
  before pushing: `git show --stat <commit>` and compare file count against
  the original patch's own `git show --stat <original-sha>`.

### 4. Sanity-check equivalence

```
git range-diff <OLD_TAG>..sjsonnet-<OLD_TAG>-x <NEW_TAG>..sjsonnet-<NEW_TAG>-x
```

Every patch should show as equivalent (`=`) or a small, explainable rework
(`!` with a small diff-of-diff). A patch showing as substantially different
content means something went wrong in step 3 — stop and investigate before
continuing.

### 5. Replay the release-coordinates commit

Same as any other patch — `git cherry-pick` it (don't treat it as special
enough to skip the pattern used for everything else in step 3):

```
git cherry-pick <release-coordinates-sha>
```

If `build.mill`'s `pomSettings` shape hasn't changed upstream, this applies
clean. If it conflicts (e.g. the `Developer(...)` call's field names
changed — mill's `PomSettings`/`Developer` API does evolve across mill
versions), resolve it the same way as any other conflict in step 3: don't
guess field names, check the file's other, still-upstream `Developer(...)`
call already present for the currently-valid shape, or look up the pinned
mill version's actual case class if genuinely unsure
(`//| mill-version:` at the top of `build.mill` → look up
`libs/javalib/src/mill/javalib/publish/model.scala` at that tag in
`com-lihaoyi/mill` — but check the in-repo example first, it's usually
enough and faster). Keep the same intent as the prior cycle's version of
this commit: don't touch fields the original didn't touch (e.g.
`licenses` — that tracks the project's actual license, not a fork
customization), and add to upstream's `developers` list rather than replace
it. Stage only `build.mill` explicitly (see the staging note in step 3).

### 6. Verify

```
./mill _.jvm[_].__.checkFormat
```

(Compile and full test already ran after each patch in step 3 — this is
just the formatting check plus a final confirmation, not a first-time run.)
```
./mill _.jvm[_].__.compile
./mill _.jvm[_].__.test
```

Also confirm:
- `sjsonnet/version` matches `NEW_TAG` (or the project's current convention
  for that file — check how upstream is deriving it as of `NEW_TAG`, this
  has changed before, e.g. "Derive build version from current commit").
- No merge commits: `git log --merges NEW_TAG..sjsonnet-<NEW_TAG>-x` is empty.
- Spot-check each patch's own behavior still works end to end: `??`
  (null-coalesce), `?.` (safe-select), string-indexed object access,
  the numeric semantics in `madr-better-nums.md`.

Do not proceed to step 7 if any of the above fails.

### 7. Push

```
git push origin sjsonnet-<NEW_TAG>-x
```

Never force-push over an existing branch of the same name without explicit
confirmation from the user.

## Known divergences from upstream

Behaviour where this fork deliberately differs from `databricks/sjsonnet`. Each entry is a
decision, not a bug: **do not "fix" one back toward upstream without asking.** When replaying
patches (step 3), expect conflicts in these areas and resolve *toward this table*.

The rationale for the numeric entries lives in `madr-better-nums.md`; this is the index.

| Area | Divergence | Why |
|---|---|---|
| Numeric core | Literals and the infix operators are exact (`Int64`/`Float64`/`Dec128`) rather than binary64 | `madr-better-nums.md`. Drives every entry below. |
| Out-of-double range | `1e309` is a valid value, not a parse error; `1e308 + 1e308` succeeds | Upstream raises `Overflow`; exactness is the point of the rework |
| `std.*` | Deliberately still narrows to `Double` (`std.sum`, `std.mod`, `floor`/`ceil`/`round`, trig) | Scope rule: the core is exact, the stdlib is not. Exact-aggregation belongs in `xtr`. |
| `std.format` | `%s` and the integer conversions `%d %i %u %o %x %X` are exact; `%e %E %f %F %g %G` narrow | Rounding is defined behaviour for float conversions only. Upstream has doubles only, so its output differs for integers past 2^53. |
| `Interpreter.interpret` | Returns `ujson.Value`, whose numbers are `Double` — so the convenience overload **narrows**, yielding `Infinity` for out-of-range values and rounding past 2^53 | Not fixable without changing ujson. Use `interpret0(txt, path, visitor)` with a Dec128-aware visitor; that is what xtrasonnet does. |
| Test harness | 10 goldens skip-listed in `FileTests.scala` because `ujson.Value` cannot express their result | Their goldens are left at upstream's text so a sync drops in clean. Coverage moved to `new_test_suite/decimal_semantics.jsonnet`, which asserts on rendered strings. |
| Scala Native 2.13 | `-0` produced by arithmetic renders `0`; 2 unit tests + 9 goldens fail on that target only | Toolchain quirk in `NumberMath.signZero`'s `-0.0` literal. Not fixed: the fork targets xtrasonnet (JVM). `Math.copySign(0.0, -1.0)` is the fix if ever needed. |
| Performance | Upstream's raw-`Double` arithmetic fast paths were removed; all arithmetic routes through `NumberMath`. Measured at ~1.0–1.25× of upstream on the regression suite — see below | Exactness is the product, not a mode: there is no opt-out, and no way to get upstream's numerics from this fork. Measured median 0.98x of upstream over 14 suite cases, worst 1.22x, so there is little to opt out of. A `Float64` operand promotes like any other, so it never survives an operation. |

### Measured performance

`./mill bench.runRegressions` against `48ff3b5a` (the commit before the numeric rework), both
checkouts alternated over three rounds on one machine, taking **min-of-3** per case — minimum
rather than mean or median, because external load only ever adds time, and two of six rounds in
one run were visibly contaminated by it. Two cases untouched by the rework held at 1.00–1.03×,
which is what says the method is sound; single-shot A/B runs were unusable and their numbers
should not be quoted.

| case | ratio vs upstream |
|---|---|
| `jdk17_suite/repeat_format`, `go_suite/manifestJsonEx`, `cpp_suite/realistic2` (controls) | 0.98–1.03× |
| `lazy_array_slice_remove`, `lazy_array_comprehension`, `bench.02` | 1.05–1.08× |
| `lazy_array_reverse_sparse`, `lazy_array_sparse_indexing` | 1.07–1.11× |
| `array_copy_views` | 1.22× |
| `cpp_suite/bench.06` | 1.06–1.24× |

**On `runRegressions`' own settings, do not trust anything under a few ms.** The suite runs one
warmup iteration and one measurement iteration, so a sub-millisecond case is read before JIT has
settled and fork startup dominates. Re-running the same three cases five times at those settings
put the *controls* at 0.66–1.22×, which by the rule above invalidates the run. `bench.06` was
recorded at 7.3× that way; with three warmup and five measurement iterations the same comparison is
2.9–3.2×. Prefer `-wi 3 -i 5 -f 1` via `org.openjdk.jmh.Main` on the bench classpath when a small
case matters — `runRegressions` hardcodes its JMH arguments and cannot pass them through.

**`bench.06` was `BigDecimal./`, and is fixed.** Not sorting, and not representation. The file ends
with `std.makeArray(2000, function(i) std.floor((i + 2) / 2))`; half of those 2000 divisions are
inexact, and an inexact `Int64 / Int64` promoted straight to `BigDecimal.divide` at `DECIMAL128`,
which costs ~500-700ns because its exact-remainder branch strips trailing zeros by repeated Knuth
division. Every sort in the file is fast, and the mixed `Int64`/`Float64` `assertEqual` over 2000
elements costs 0.22 ms. `NumberMath.divideExact` now settles terminating quotients in `Long`
arithmetic instead — worth ~3× on `Int64 / Dec128` and `Dec128 / Dec128` as well; see
`madr-better-nums.md` → "Notes".

### Replaying the numeric patch: where the conflicts are

The numeric rework is the fork's largest patch (~2 900 lines over 41 files vs `0.7.3`). Ordered by
how much trouble each file gives on replay:

| File | What to expect |
|---|---|
| `Evaluator.scala` | Worst. Upstream keeps adding raw-`Double` fast paths; ours routes arithmetic through `NumberMath` and deleted the comprehension arithmetic pipeline. Any new upstream fast path needs the same treatment: comparisons and bitwise/shift may stay raw, arithmetic may not. |
| `Val.scala` | The `Int64`/`Float64`/`Dec128` split plus two 256-entry pools. Upstream's `cachedNum` call sites are the hazard — a new one on an integral value silently costs `BigDecimal` promotion later. |
| `StaticOptimizer.scala` | The constant folder must fold through `NumberMath`'s `try*` variants, or a folded chain disagrees with the evaluator. |
| `Parser.scala` | Number-literal grammar. Underscore stripping must happen *before* `Val.Num` sees the text, or `decIndex`/`expIndex` are wrong. |
| `Materializer.scala` | Six numeric dispatch sites, plus `RangeArr`/`ByteArr` compact paths that write raw `Double` deliberately. |
| `Renderer.scala` + the four renderers | `renderNum`/`renderDec128`/`truncatedNumDigits`. Mostly additive. |
| `SetModule` / `StringModule` / `TypeModule` | The only `std` files we diverge in, and only where the floor requires it. |
| `MathModule` | `max`/`min`/`abs`/`floor`/`ceil`/`round`/`clamp` carry pass-through guards so they cannot answer with a number that was never an input. Everything else in the file is upstream's, deliberately — `std` is inexact by design and `xtr` is the exact path. Guards read as bug fixes, so they usually survive an upstream rework intact. |
| `Format.scala` | `%s` and the integer conversions. Upstream churns this file heavily. |
| `ByteRenderer.scala` | Has its own fused `materializeDirect` that bypasses the visitor entirely — easy to miss, and it is the CLI's default path. |

`NumberMath.scala` and `JsonVisitor.scala` are ours alone and never conflict.

### Deliberately not done

- **`std.parseYaml` narrows on ingest** (builds a `ujson.Value` first) and on the JVM throws
  `NumberFormatException` for `'a: 9223372036854775808'`. Fixing it means rewriting YAML ingest
  across all three `Platform.scala` files — a rewrite, not a patch. Accepted as-is.
- **`std.manifestIni` / `std.manifestPythonVars`** round-trip through `Materializer`'s
  `ujson.Value`, so integers past 2^53 surface as *quoted strings* and `1e400` as `Infinity`,
  unlike their exact siblings `manifestJson`/`manifestToml`/`manifestYamlDoc`/`manifestXmlJsonml`.
  Fixing means diverging in `ManifestModule.scala`, an upstream-churned file. Logged, not fixed.
- **CI (`pr-build.yaml`) is not run by this fork** — it triggers only on pull requests targeting
  `master`, and this fork pushes integration branches directly. The Native 2.13 failures above are
  therefore not a CI problem; verify locally per step 6.

## Ask, don't guess

- Which upstream tag is `NEW_TAG`.
- Whether a conflicting patch should be reworked vs. dropped as obsolete.
- Whether to squash a multi-commit patch during replay.
- Anything not covered above that changes what gets published.

## Non-goals

This playbook only covers syncing the fork forward to a newer upstream tag.
It does not cover:
- Publishing to Sonatype (`release.sh`).
- Upstream contribution workflow.
- Any locally-named branches (`null-coalesce`, `safe-select`, `pos`, etc.)
  that may or may not exist in a given session — these are scratch/dev
  space only, not durable references, and not part of this procedure. The
  durable record of each patch is the commit already living inside the
  most recent pushed `sjsonnet-*-x` branch (see step 1).
