# Dependency updates

Started 2026-11-20. Dependencies are updated one at a time, lowest risk first.
To see what is outdated: `clojure -M:outdated --reporter=table` (antq).

Status: `[ ]` todo · `[x]` done · `[!]` blocked / held back (reason in notes)

## Procedure for each item

1. Pick the next `[ ]` row below.
2. Read the dependency's changelog for breaking changes between the two versions.
3. Bump the version everywhere it appears (deps.edn `:deps`, `:dev`, `:test`, `:outdated`, bb.edn).
4. Format: `bb fmt fix` (whenever .clj files changed), then `bb fmt check` must be clean.
   Check:
   - `bin/kaocha`: unit + integration (integration needs Docker)
   - `bb test-bb`
   - `bb compoje deploy --dry-run example-stacks/nginx/stack.tpl.yml`
5. Green → mark `[x]` and add a note. Failing → fix it if the fix is small, otherwise revert and mark `[!]` with the reason.
6. Commit (done by a human), then move to the next row.

Babashka uses its built-in fs, process, cheshire, clj-yaml, selmer, timbre and tools.cli, so for those the bb checks
only confirm compatibility. The JVM tests (`bin/kaocha`) exercise the new versions.

## Checklist

| #  | Status | Dependency                                               | Where                       | From → To           | Risk / notes |
|----|--------|----------------------------------------------------------|-----------------------------|---------------------|--------------|
| 1  | [x]    | org.clojure/clojure                                      | deps                        | 1.12.0 → 1.12.7     | patch. kaocha 11/36 green, bb green, dry-run OK. Dry-run exposed a pre-existing bug (see below) |
| 2  | [x]    | org.clojure/tools.logging                                | deps                        | 1.3.0 → 1.3.2       | patch. kaocha 11/36 green, bb green, dry-run OK |
| 3  | [x]    | clj-commons/clj-yaml                                     | deps                        | 1.0.29 → 1.0.30     | patch; config/render YAML. kaocha 11/36 green, bb green, dry-run OK (bb uses built-in clj-yaml) |
| 4  | [x]    | org.slf4j/* (jcl-over, jul-to, log4j-over, slf4j-simple) | :dev, :test, :outdated      | 2.0.17 → 2.0.20     | patch; one step. 7 entries bumped; kaocha 11/36 green, bb green, dry-run OK, :outdated resolves |
| 5  | [x]    | luposlip/json-schema                                     | deps                        | 0.4.6 → 0.4.8       | not used in code yet. kaocha 11/36 green, bb green, dry-run OK, `json-schema.core` loads on JVM |
| 6  | [x]    | babashka/fs                                              | deps                        | 0.5.24 → 0.5.34     | heavily used; changelog: no breaking changes. kaocha 11/36 green, bb green, dry-run OK (bb uses built-in fs) |
| 7  | [x]    | io.github.matthewdowney/rich-comment-tests               | :dev, :test, bb.edn test-bb | v1.0.3 → v1.0.5     | test infra; upstream: hang + auto-resolved keyword fixes. 3 places bumped; kaocha 11/36 green, bb test-bb 6/16 green, dry-run OK |
| 8  | [x]    | com.taoensso/timbre                                      | deps                        | 6.6.1 → 6.8.0       | changelog: non-breaking (6.7 drops Clojure 1.9, throws on invalid compile-time min-level; neither applies). kaocha 11/36 green, bb green, dry-run OK; JVM `compoje.cli/main -v` dry-run logs OK |
| 9  | [x]    | com.amperity/vault-clj                                   | deps                        | 2.2.586 → 2.3.594   | additive only (Azure auth, header-merge fix). Not built into bb, so bb loads it too. kaocha 11/36 green incl. Vault integration, bb green, dry-run OK, bb loads vault.client.http + kv.v2 |
| 10 | [x]    | selmer/selmer                                            | deps                        | 1.12.62 → 1.13.5    | only breaking change is the `script` tag (unused). JVM render of nginx + all-opts-stack byte-identical between 1.12.62 and 1.13.5. kaocha 11/36 green, bb green, dry-run OK (bb uses built-in selmer) |
| 11 | [x]    | org.clojure/tools.cli                                    | deps                        | 1.1.230 → 1.4.256   | 1.4 deprecates `:in-order true` for `:subcommand :explicit`; kept `:in-order` (no warning, same result; old bb per `:min-bb-version` lacks `:subcommand`). JVM parse of -v -v, --set x2, --stack, --help, bad opt OK; bb `--set` override renders 256m. kaocha 12/37, bb green, dry-run OK |
| 12 | [x]    | metosin/malli                                            | deps                        | 0.17.0 → 0.20.3     | not used in code yet; pre-1.0. Breaking: 0.18 `parse` Tag records + JSON-Schema `.` separator, 0.19 json-transformer enum inference (none apply). `malli.core` validate works on bb + JVM. kaocha 12/37, bb green, dry-run OK |
| 13 | [x]    | dev.weavejester/cljfmt                                   | bb.edn                      | 0.13.0 → 0.16.6     | `bb fmt` was broken on 0.13.0 under bb ("Not implemented in the babashka version of cljfmt yet"); works on 0.16.6. 0.15 renamed alignment options, 0.16 ignores .clj config (no cljfmt config in repo). `bb fmt check` now flags 11 files; reformatting is a separate follow-up commit. kaocha 12/37, bb green, dry-run OK |
| 14 | [x]    | djblue/portal                                            | :dev                        | 0.58.5 → 0.69.2     | 0.67 binds to localhost by default (was 0.0.0.0). Exclusion kept; data.json 2.4.0 still on :dev path. `clojure -M:dev` starts/stops Portal on localhost. 0.69.2 was 7 days old when bumped. kaocha 12/37, bb green, dry-run OK |
| 15 | [x]    | babashka/process                                         | deps                        | 0.5.22 → 0.6.25     | no breaking changes (exec resolution, :discard redirect, leak + reflection fixes). `shell` failure ex-data (`:type :babashka.process/error`, `:cmd`, string `:err`) identical on JVM 0.6.25 and bb built-in. kaocha 12/37, bb green, dry-run OK |
| 16 | [x]    | cheshire/cheshire                                        | deps                        | 5.13.0 → 6.2.1      | major, but only breaking change is Windows pretty-print line breaks (compoje does not pretty-print). jackson-core/smile/cbor 2.17.0 → 2.21.8, no jackson-databind on path. JSON round-trip OK; kaocha 12/37 incl. Vault integration, bb green (built-in cheshire), dry-run OK |
| 17 | [x]    | org.testcontainers/testcontainers                        | :dev, :test                 | 1.21.4 → 2.0.5      | major (JUnit 4 removed, modules renamed); clj-test-containers 0.7.4 only uses core classes and works. 2.0.5 overrides the transitive 1.17.6. kaocha 12/37 incl. Vault integration, bb green, dry-run OK |

### Found along the way

- Step 1: `bb compoje deploy` crashed for every stack (ClassCastException in `deep-merge`) because
  `compoje.context/config->context` passed its `& more` seq to `deep-merge` without `apply`.
  This was not caused by the bump. Fixed in src/compoje/context.clj, with a regression test
  `unit.compoje.context-test/config->context-test`. Commit it separately from the Clojure bump.
- Step 10: the rendered stack still contains the whole `compoje:` config section, and it is written
  to `stack.generated.yml` and passed to `docker stack deploy`. Compose only allows `x-` prefixed
  extra top-level keys; confirmed with `docker stack config -c`: "additional property 'compoje' is not allowed". The committed `stack.generated.yml`
  files predate the config-in-stack-file change and are stale. Not caused by any bump; needs a design
  decision. **Fixed:** key renamed to `x-compoje:` (legacy `compoje:` still read with a warning);
  both example stacks pass `docker stack config`, and the stale generated files were regenerated.
- Step 13: with cljfmt 0.16.6, `bb fmt check` reports 11 files (src + test) with incorrect formatting,
  mostly consecutive blank lines and indentation. **Done:** formatted with `bb fmt fix` in its own commit (fc7f73e).

Not reported by antq (already current): kaocha 1.91.1392, clj-test-containers 0.7.4, slf4j-timbre 0.4.1, bb spec.alpha git dep.

## When finished

Completed 2026-11-21: all 17 rows done, none held back. `clojure -M:outdated` reports
"All dependencies are up-to-date." CHANGELOG.md `[Unreleased] → Changed` has the consolidated entry.
