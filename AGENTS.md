# AGENTS.md

compoje is a templating and deploy tool for Docker Swarm stacks, written in Clojure. **compoje is run with Babashka** (`bb compoje ...`, as a bb task here or in users' bb.edn); the JVM (deps.edn) is used for development, the REPL and the kaocha tests. It renders a compose-file template with Selmer, using a context built from config, CLI `--set` args and secret providers (HashiCorp Vault, ansible-vault). It then shells out to `docker stack deploy`. Configs and secrets get content hashes in their names, so changing a file creates a new immutable Swarm object.

Documentation lives under docs/. Keep existing files in their current format (README.org and docs/docs.org are Org-mode; edit them as Org). **Write new documentation as Markdown files under docs/** (e.g. docs/known-issues.md, docs/dependency-updates.md).

## Commands
- **Run tests with `bin/kaocha`** (the canonical test command; it wraps `clojure -M:test`). Extra args pass through to kaocha: `bin/kaocha unit`, `bin/kaocha integration`, `bin/kaocha --focus unit.compoje.render-test`, `bin/kaocha --watch`.
- Run CLI (bb): `bb compoje deploy [--dry-run] [--set a.b=v] path/to/stack.tpl.yml`
- Babashka tests: `bb test-bb` (all rich-comment-tests in src/ plus the namespaces listed in test/bb_test_runner.clj)
- Format: `bb fmt check` / `bb fmt fix` (cljfmt). **Run `bb fmt fix` after every code change**, before tests and before handing work back; `bb fmt check` must be clean.
- Outdated deps: `clojure -M:outdated` (antq)
- Local Vault for REPL: `bb vault-dev` (root token `myroot`)
- REPL: `:dev` alias (adds dev/, test/, test-resources/, portal)

Integration tests need a running Docker daemon (testcontainers): test/integration/vault_test.clj starts `hashicorp/vault`; test/integration/ansible_vault_test.clj builds test-resources/ansible_vault/Dockerfile (ansible-core) and cross-checks encrypt/decrypt in both directions. Regenerate ansible-vault fixtures with `clojure -M:dev -m integration.gen-ansible-vault-fixtures`.
- Tests that must also pass in Babashka are listed in test/bb_test_runner.clj; add new bb-relevant test namespaces there.

## Layout
- src/compoje/cli.clj: entry point `compoje.cli/main` (bb task) / `-main` (JVM). Parsing, help and subcommands via lambdaisland/cli (`cmdspec`, strict flags); the `deploy` command's flags live in its var metadata. Option precedence: `docker-defaults` < x-compoje < `--set` < docker flags (`cli-config->context`). Commands return `::done`; `main` exits 1 otherwise (usage errors), unless help was asked for. Commands: `deploy` and `migrate` (compoje.edn → x-compoje).
- migrate.clj: `migrate!` moves an old compoje.edn into `<dir>/stack.tpl.yml` as `x-compoje:` (text prepended, template not re-parsed); fails on aero tags. compoje.edn is being phased out. See docs/migrate-from-compoje-edn.md.
- config.clj: reads a YAML stack file and returns the map under its top-level `x-compoje:` key (legacy `compoje:` still read, with a warning), deep-merged with `--set` args.
- context.clj: builds the render context (`:values`, `:secrets`, `:work-dir`, `:template-dir`, `:docker`).
- providers.clj: `SecretsProvider` protocol + registry atom, `provide-secrets` (results keyed by keyword secret name), shared `secret->file!`. providers/vault.clj (HashiCorp Vault), providers/ansible_vault.clj (glue for the ansible-vault library). providers/file.clj is a stub.
- src/ansible_vault/ (`ansible-vault.core`, `.crypto`, `.yamlstar`): standalone ansible-vault format library (decrypt + encrypt). It must not require any compoje namespace; it is meant to be extracted. See docs/ansible-vault.md.
- render.clj: Selmer rendering plus custom tags `file-hash`, `file-path`, `to-yaml` and the filter `hash-file`.
- deploy/docker.clj: builds and shells out to the `docker stack deploy` command line.
- utils.clj: `deep-merge`, `set-args->map`, misc.
- compose-spec/: compose JSON schema. example-stacks/: sample stacks.

Deploy flow: `deploy-context` (`cli-config->context` → `providers/provide-secrets` → `context/final-context`) → `render/render <template-dir>/stack.tpl.yml` → write `stack.generated.yml` next to the template → `docker/deploy`.

## Template syntax (important)
Selmer is called with `{:tag-open \< :tag-close \>}`, so the delimiters are NOT `{{ }}` / `{% %}`:
- variables: `<{ values.some-key }>`
- tags: `<% file-hash 'config/nginx.conf' %>`

Rendering runs inside `without-escaping`. Paths in tags are relative to the template dir.

A stack file is a compose file plus an `x-compoje:` key (docker, providers, secrets, values). The whole file goes through Selmer, and the `x-compoje:` section is read as YAML. The section stays in the rendered output; the `x-` prefix is what lets `docker stack deploy` accept it. Validate a rendered stack with `docker stack config -c <file>`.

## Conventions
- Babashka is the production runtime, so code must stay bb-compatible: no deps or JVM features bb lacks (see the data.json exclusion note in deps.edn). Green JVM tests are not enough; also verify with `bb test-bb` and `bb compoje deploy --dry-run example-stacks/nginx/stack.tpl.yml`.
- bb ships built-in fs, process, cheshire, selmer and timbre (and clj-yaml/tools.cli, which compoje no longer uses). At runtime those built-in versions are used, not the deps.edn versions, so upgrading them in deps.edn mainly affects JVM dev/tests.
- Tests: every side-effect-free function needs tests; small ones as rich-comment-tests (`^:rct/test` comment blocks, expectations are literal data), larger ones in kaocha. Kaocha suites under test/unit and test/integration; namespaces are prefixed `unit.` / `integration.`. Rich-comment-tests in src/ run via test/unit/rct_test.clj (JVM) and `bb test-bb`.
- Logging: timbre in cli.clj, logging.clj and deploy/docker.clj; clojure.tools.logging elsewhere.
- YAML is parsed and generated with yamlstar (`yamlstar.core/load`, `dump`): YAML 1.2, pure Clojure, loaded from deps.edn in bb (not built in). `config/read-config-yaml` keywordizes keys. Known limits of yamlstar 0.1.23: `dump` writes multi-line strings without a trailing newline as `|`, adding one (`"a\nb"` round-trips to `"a\nb\n"`); custom tags (e.g. `!vault`, compose `!reset`) throw "Unknown tag". yamlstar excludes `org.clojure/data.json` in deps.edn; keep that, as data.json > 2.4.0 breaks bb.
- `*.generated.yml` files are render output. Regenerate them, don't hand-edit them.
- Never commit decrypted secrets (e.g. a local `decrypted.yml`).
- Record user-visible changes under `## [Unreleased]` in CHANGELOG.md.
- Open problems deferred for later review are in docs/known-issues.md. Add newly found ones there instead of fixing them unasked; remove an entry when it is fixed.
