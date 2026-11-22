# AGENTS.md

compoje is a templating and deploy tool for Docker Swarm stacks, written in Clojure. **compoje is run with Babashka** (`bb compoje ...`, as a bb task here or in users' bb.edn); the JVM (deps.edn) is used for development, the REPL and the kaocha tests. It renders a compose-file template with Selmer, using a context built from config, CLI `--set` args and secret providers (HashiCorp Vault, ansible-vault). It then shells out to `docker stack deploy`. Configs and secrets get content hashes in their names, so changing a file creates a new immutable Swarm object.

Documentation lives under docs/. Keep existing files in their current format (README.org and docs/docs.org are Org-mode; edit them as Org). docs/README.md is the documentation index; add new docs to it. **Write new documentation as Markdown files under docs/** (e.g. docs/known-issues.md, docs/dependency-updates.md).

## Commands
- **Run tests with `bin/kaocha`** (the canonical test command; it wraps `clojure -M:test`). Extra args pass through to kaocha: `bin/kaocha unit`, `bin/kaocha integration`, `bin/kaocha --focus unit.compoje.render-test`, `bin/kaocha --watch`.
- Run CLI (bb): `bb compoje deploy [--dry-run] [--set a.b=v] path/to/stack.tpl.yml`
- Babashka tests: `bb test-bb` (all rich-comment-tests in src/ plus the namespaces listed in test/bb_test_runner.clj)
- Format: `bb fmt check` / `bb fmt fix` (cljfmt). **Run `bb fmt fix` after every code change**, before tests and before handing work back; `bb fmt check` must be clean.
- Lint: `bb lint` runs clj-kondo (as a bb pod, over src/, test/, script/ and install.clj) and `clojure-lsp diagnostics` (the `clojure-lsp` binary if on PATH, else the `:lsp` alias). **Run it after every code change, with `bb fmt fix`; it must be clean** (CI fails otherwise). Fix findings rather than silencing them; real false positives go in .clj-kondo/config.edn with a comment.
- Vulnerabilities: `bb vuln-scan` (clj-watson over all deps.edn aliases): NVD database with `NVD_API_KEY`, else GitHub Advisory Database with `GITHUB_TOKEN`; `--nvd`/`--github` force one. Never put the key on a command line. Findings: trace with `bb deps-why <group/artifact>` (alias paths + jars embedding shaded copies); suppress only per the rules in dependency-check-suppressions.xml / docs/dependency-updates.md (false positives permanently with evidence; real but unreachable ones with `until`, plus a known-issues entry). CI runs it weekly and when deps change (.github/workflows/vulnerabilities.yml).
- Outdated deps: `clojure -M:outdated` (antq)
- One-line installer: install.clj (repo root, standalone bb script: downloads compoje.jar + .sha256 from a GitHub release, writes a `compoje` launcher to ~/.local/bin). Keep it dependency-free (bb built-ins only) and compatible with the release asset names. Tests: test/bb/install_test.clj (bb only, in bb_test_runner).
- Build the release jar: `bb build-jar` → target/compoje.jar (run with `bb target/compoje.jar`). Version: resources/compoje/VERSION (`compoje --version`). Releases: tag `vX.Y.Z`, see docs/releasing.md; CI and release workflows in .github/workflows/. Install channels (bb task, bbin via `:bbin/bin` in bb.edn, jar) are in docs/install.md. cljfmt is a `fmt` task extra-dep, so top-level bb.edn deps stay runtime-only.
- Local Vault for REPL: `bb vault-dev` (root token `myroot`)
- REPL: `:dev` alias (adds dev/, test/, test-resources/, portal)

Integration tests need a running Docker daemon (testcontainers): test/integration/vault_test.clj starts `hashicorp/vault`; test/integration/ansible_vault_test.clj builds test-resources/ansible_vault/Dockerfile (ansible-core) and cross-checks encrypt/decrypt in both directions. Regenerate ansible-vault fixtures with `clojure -M:dev -m integration.gen-ansible-vault-fixtures`. test/integration/docker_client_test.clj talks to the local daemon (unix socket, `docker system dial-stdio`). test/integration/swarm_engines_test.clj builds test-resources/swarm/dind/Dockerfile (privileged Docker-in-Docker swarm with TLS on 2376 and sshd; needs a local `ssh` client) and checks that `docker stack deploy` and the docker-api engine produce identical services/networks/secrets/configs for the reference stack, plus managed secrets over ssh dial-stdio, ssh socket forwarding and mutual TLS.
- Tests that must also pass in Babashka are listed in test/bb_test_runner.clj; add new bb-relevant test namespaces there.

## Layout
- src/compoje/cli.clj: entry point `compoje.cli/main` (bb task) / `-main` (JVM). Parsing, help and subcommands via lambdaisland/cli (`cmdspec`, strict flags); the `deploy` command's flags live in its var metadata. Option precedence: `docker-defaults` < x-compoje < `--set` < docker flags (`cli-config->context`). Commands return `::done`; `main` exits 1 otherwise (usage errors), unless help was asked for. Commands: `init` (scaffold, src/compoje/init.clj + templates in resources/compoje/init/, copied verbatim except `@STACK@`), `deploy`, `render` and `migrate` (compoje.edn → x-compoje, local-path secrets → in-memory).
- migrate.clj: `migrate!` moves an old compoje.edn into `<dir>/stack.tpl.yml` as `x-compoje:` (text prepended, template not re-parsed; fails on aero tags), then runs migrate_secrets.clj: local-path secrets → `x-compoje: {from}` definitions, by line-level text edits verified by re-parsing (unsafe cases are skipped and reported). compoje.edn is being phased out. See docs/migrate-from-compoje-edn.md.
- config.clj: reads a YAML stack file and returns the map under its top-level `x-compoje:` key (legacy `compoje:` still read, with a warning), deep-merged with `--set` args.
- context.clj: builds the render context (`:values`, `:secrets`, `:work-dir`, `:template-dir`, `:docker`).
- providers.clj: `SecretsProvider` protocol + registry atom, `provide-secrets` (results keyed by keyword secret name), shared `secret->file!`. providers/vault.clj (HashiCorp Vault), providers/ansible_vault.clj (glue for the ansible-vault library), providers/file.clj (plain yaml/json/edn/raw files). Path helpers `expand-home`/`resolve-path` live in providers.clj.
- src/ansible_vault/ (`ansible-vault.core`, `.crypto`, `.yamlstar`): standalone ansible-vault format library (decrypt + encrypt). It must not require any compoje namespace; it is meant to be extracted. See docs/ansible-vault.md.
- schema.clj: malli validation of the x-compoje config (`validate`: closed `docker`, built-in provider/secret keys, cross checks, --set boolean coercion); called by `cli/validated-config` in `cli-config->context`. Add new config keys here and in docs/configuration.md.
- stack.clj: pure handling of managed (in-memory) secrets/configs: top-level definitions with `x-compoje: {from|template}` → `managed-objects` + `externalize`; `cli/prepare-stack` wires it in, `deploy/docker` creates them via `docker secret|config create NAME -`.
- render.clj: Selmer rendering plus custom tags `file-hash`, `file-path`, `to-yaml` and the filter `hash-file`.
- docker/: Docker Engine API without the docker CLI (used by the coming API deploy engine). endpoint.clj resolves the daemon like the CLI (--context, DOCKER_HOST, DOCKER_CONTEXT, currentContext, ~/.docker/contexts meta + TLS dirs); http.clj is a minimal HTTP/1.1 client over streams; tls.clj builds an SSLContext from PEM (PKCS#8, PKCS#1, SEC1; no BouncyCastle, bb-safe); client.clj has the transports (unix socket, TCP+mTLS, ssh `docker system dial-stdio`, ssh socket forward), keep-alive connection, API version negotiation (capped at `max-api-version`) and JSON `request`. bb can't open SSL server sockets, so the in-process TLS test server runs on the JVM only.
- deploy/swarm_convert.clj: compose → Swarm API specs for the docker-api engine (pure; mirrors docker/cli convert). `parse-stack` reads leading-zero ints as octal (YAML 1.1, like docker). Unsupported keys error via `validate`'s whitelist `schema`. test-resources/swarm/ref-stack/stack.yml covers every supported key; its expected-plan.json was verified identical to `docker stack deploy` output (regenerate with `swarm-convert-test/regenerate!` and re-check against docker). deploy/interpolate.clj does compose `${VAR}` substitution (as docker stack deploy) before conversion; deploy/api.clj applies the plan over compoje.docker.client; deploy/registry_auth.clj builds X-Registry-Auth. Engines: `--deploy-driver` / `x-compoje docker.driver` → `docker-cli` (default) or `docker-api`.
- deploy/docker.clj: builds and shells out to the `docker stack deploy` command line.
- utils.clj: `deep-merge`, `set-args->map`, misc.
- example-stacks/: sample stacks.

Deploy flow: `deploy-context` (`cli-config->context` → `providers/provide-secrets` → `context/final-context`) → `render/render <template-dir>/stack.tpl.yml` → `docker/deploy` pipes it to `docker stack deploy --compose-file -` (run in the template dir); the rendered stack is never written to disk (`compoje render [-o FILE]` prints/saves it). Logs go to stderr, data to stdout.

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
