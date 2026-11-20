# Dependency updates

Started 2026-11-20. Dependencies are updated one at a time, lowest risk first.
To see what is outdated: `clojure -M:outdated --reporter=table` (antq).

Status: `[ ]` todo · `[x]` done · `[!]` blocked / held back (reason in notes)

## Procedure for each item

1. Pick the next `[ ]` row below.
2. Read the dependency's changelog for breaking changes between the two versions.
3. Bump the version everywhere it appears (deps.edn `:deps`, `:dev`, `:test`, `:outdated`, bb.edn).
4. Check:
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
| 6  | [ ]    | babashka/fs                                              | deps                        | 0.5.24 → 0.5.34     | heavily used |
| 7  | [ ]    | io.github.matthewdowney/rich-comment-tests               | :dev, :test, bb.edn test-bb | v1.0.3 → v1.0.5     | test infra |
| 8  | [ ]    | com.taoensso/timbre                                      | deps                        | 6.6.1 → 6.8.0       | check .clj-kondo encore config |
| 9  | [ ]    | com.amperity/vault-clj                                   | deps                        | 2.2.586 → 2.3.594   | covered by Vault integration test |
| 10 | [ ]    | selmer/selmer                                            | deps                        | 1.12.62 → 1.13.5    | custom `<{ }>`/`<% %>` delimiters, `add-tag!`, `without-escaping` |
| 11 | [ ]    | org.clojure/tools.cli                                    | deps                        | 1.1.230 → 1.4.256   | check parse-opts `:multi`/`:update-fn` |
| 12 | [ ]    | metosin/malli                                            | deps                        | 0.17.0 → 0.20.3     | not used in code yet; pre-1.0 |
| 13 | [ ]    | dev.weavejester/cljfmt                                   | bb.edn                      | 0.13.0 → 0.16.6     | run `bb fmt check`; keep reformatting separate |
| 14 | [ ]    | djblue/portal                                            | :dev                        | 0.58.5 → 0.69.2     | keep `org.clojure/data.json` exclusion; check `clojure -M:dev` |
| 15 | [ ]    | babashka/process                                         | deps                        | 0.5.22 → 0.6.25     | `shell` + ex-data `:type` in deploy/docker.clj |
| 16 | [ ]    | cheshire/cheshire                                        | deps                        | 5.13.0 → 6.2.1      | major; Jackson upgrade; providers/vault.clj |
| 17 | [ ]    | org.testcontainers/testcontainers                        | :dev, :test                 | 1.21.4 → 2.0.5      | major; clj-test-containers 0.7.4 may not support 2.x |

### Found along the way

- Step 1: `bb compoje deploy` crashed for every stack (ClassCastException in `deep-merge`) because
  `compoje.context/config->context` passed its `& more` seq to `deep-merge` without `apply`.
  This was not caused by the bump. Fixed in src/compoje/context.clj, with a regression test
  `unit.compoje.context-test/config->context-test`. Commit it separately from the Clojure bump.

Not reported by antq (already current): kaocha 1.91.1392, clj-test-containers 0.7.4, slf4j-timbre 0.4.1, bb spec.alpha git dep.

## When finished

- Rerun antq; only `[!]` rows should remain outdated.
- Add a consolidated "Dependency upgrades" entry to CHANGELOG.md under `[Unreleased] → Changed`.
