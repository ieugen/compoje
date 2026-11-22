# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed
- **Breaking:** provider results are under `secrets` in templates again, `<{ secrets.<name>.<key> }>` (and `x-compoje: {from: secrets.<name>.<key>}`), as in compoje.edn stacks, so a secret name can never overwrite another context key. 0.3.0 had them at the top level (`<{ <name>.<key> }>`). `compoje migrate <dir>` rewrites 0.3.0 references in the stack file and in `x-compoje: {template: ...}` files. The check that rejected secret names like `values` is gone, as names can no longer collide.

### Fixed
- Stacks migrated from compoje.edn rendered empty secret values: their `<{ secrets.<name>... }>` references didn't match where 0.3.0 put provider results.
- Undefined template variables rendered silently as empty strings. `deploy` and `render` now fail on an undefined `secrets.` path or a top-level reference to a secret, and warn about other undefined variables (unless they use `|default`). See docs/configuration.md, "Template variables".

### Added
- Shell completion: `compoje completion bash|zsh` prints a script that completes commands, flags, flag values (engines, `--resolve-image`, docker contexts, `--set` paths) and stack files/directories, for `compoje ...` and `bb compoje ...` (other `bb` words still use bb's completion). The one-line installer installs it for bash (`--no-completion` to skip). See docs/install.md, "Shell completion".

## [0.3.0] - 2026-11-23

### Added
- Validation of the `x-compoje` configuration (malli) in `deploy` and `render`: unknown or mistyped keys with "did you mean", allowed values, required provider/secret settings for the built-in providers, undeclared providers, duplicate names, secret names that would overwrite context keys. All problems are reported at once. See docs/configuration.md, "Validation".
- `init` command: `bb compoje init [--stack NAME] [--force] <dir>` scaffolds a stack directory (stack.tpl.yml with an x-compoje section, an nginx service with an in-memory templated config, html/index.html, .gitignore). See docs/cli.md.
- Template filters `yaml-str` (quote and escape a value for YAML, including `$`) and `escape-dollar` (`$` → `$$`), so secret values survive YAML and docker's variable substitution unchanged. See docs/templates.md, "Escaping values".
- Development: `bb lint` (clj-kondo + clojure-lsp diagnostics, run in CI) and `bb vuln-scan` (clj-watson: NVD database with an NVD API key, else the GitHub Advisory Database; weekly CI workflow), with `dependency-check-suppressions.xml` for reviewed findings, and `bb deps-why <group/artifact>` to see which dependency pulls a library in (including shaded copies inside other jars). `bb fmt` now also covers `script/` and `install.clj`.
- One-line installer: `bb -e '(load-string (slurp "https://raw.githubusercontent.com/ieugen/compoje/main/install.clj"))'` installs the latest release as a `compoje` command (checksum-verified jar in `~/.local/share/compoje`, launcher in `~/.local/bin`); options `--version`, `--dir`, `--uninstall`. See docs/install.md.
- `docker-api` deploy engine (`--deploy-driver docker-api` or `x-compoje docker.driver: docker-api`): deploys through the Docker Swarm API directly, so the docker CLI is not needed. Reaches remote swarms through your docker contexts (`ssh://` via `docker system dial-stdio` on the remote host, or socket forwarding with `docker.ssh-mode: forward`; `tcp://` with mutual TLS). Creates the same services, networks, secrets and configs as `docker stack deploy` for a common subset of compose keys and fails on the others; supports `--prune`, `--resolve-image` (digest pinning) and `--with-registry-auth` (docker config.json and credential helpers), and substitutes `${VAR}` variables like docker. `docker-cli` stays the default. See docs/engines.md.
- docs/security.md: where secret values can end up, and a checklist.
- Docker secrets and configs from memory: a top-level secret/config definition with `x-compoje: {from: <context path>}` or `x-compoje: {template: <file>}` is created by compoje (`docker secret|config create NAME -`, content on stdin, name `<stack>_<key>-<sha256 prefix>` unless set, labelled with the stack) and passed to docker as external. Secret values no longer need to be written to disk. The ansible-vault and file examples use it; `local-path` is now legacy.
- `render` command: `bb compoje render [-o FILE] <stack-file>` prints the rendered stack to stdout (or writes it to a file), for debugging and piping.
- `deploy --show-secrets`: with `--dry-run`, show provider results in the printed context (redacted by default).

### Changed
- Lint cleanup: `install.clj`'s namespace is now `install` (matching the file), missing requires added, the unused `compoje.context/enhanced-context` removed.
- **Breaking:** `deploy` no longer writes `stack.generated.yml`. The rendered stack, which can contain secret values, goes to `docker stack deploy --compose-file -` on stdin (run in the stack's directory so relative `file:` paths work). The `x-compoje docker.compose-file` setting is ignored with a warning; use `render -o FILE` to save the stack.
- `deploy --dry-run` prints provider results as `<redacted>` in the context, writes logs to stderr and the rendered stack to stdout.
- Logs go to stderr; stdout carries only data (the rendered stack).
- `migrate` converts provider secrets written to files (`local-path` used by a `file:` secret/config definition) into Docker secrets created from memory (`x-compoje: {from: ...}`), and also works on stacks that already use `x-compoje` (no `compoje.edn`). Secrets it can't convert safely are left as they are and reported.

### Removed
- The unused `compose-spec/compose-spec.json` schema.

### Fixed
- `deploy` and `render` rendered `stack.tpl.yml` next to the given file instead of the given file itself; any stack file name works now.
- The HashiCorp Vault provider exited the process (`System/exit`) on errors; it now raises an error naming the secret and Vault's message, reported like other errors.
- `--set docker.prune=false` (and other booleans set with `--set`) enabled the option, because `--set` values are strings and "false" is truthy. Booleans are now converted.
- A secret named like a context key (`values`, `docker`, ...) silently replaced that key in the template; it is now an error.

## [0.2.0] - 2026-11-22

### Added
- Distribution: `compoje --version` (from `resources/compoje/VERSION`), bbin support (`:bbin/bin` in bb.edn), `bb build-jar` builds `target/compoje.jar` (runs with only bb). GitHub Actions: CI on every push, and a release workflow that publishes `compoje.jar` on `vX.Y.Z` tags. See docs/install.md and docs/releasing.md.
- File provider (`compoje.providers.file/file`): loads plain YAML, JSON, EDN or text files (format from the extension), with optional `dir`, `local-path` and `spit-key`. See docs/providers.md and `example-stacks/file/`.
- Reference documentation in docs/: cli.md, configuration.md, templates.md, providers.md (including custom providers), indexed in docs/README.md.
- `migrate` command: `bb compoje migrate [--dry-run] <dir>` moves an old `compoje.edn` into the `x-compoje:` section of `<dir>/stack.tpl.yml`; stops on aero tags (`#env`, `#or`, ...). See docs/migrate-from-compoje-edn.md.
- Quick start guide: docs/quick-start.md (install, first stack, dry-run, deploy, redeploy, overrides, troubleshooting).
- ansible-vault secrets provider (`compoje.providers.ansible-vault/ansible-vault`): reads whole files encrypted with `ansible-vault encrypt` and YAML with inline `!vault` values; password from a file, an env var or `$ANSIBLE_VAULT_PASSWORD_FILE`. See docs/ansible-vault.md and `example-stacks/ansible-vault/`.
- `ansible-vault.*` namespaces: a dependency-free Clojure implementation of the ansible-vault format (decrypt and encrypt, format 1.1/1.2, AES256), runs in Babashka, byte-compatible with ansible-core (verified by containerized cross-tests). Kept free of compoje code so it can become its own library.
- ansible-vault provider: decrypts ansible-vault encrypted files in pure Clojure (including PKCS#7 padding).
- `--with-registry-auth` deploy option, on by default and passed to `docker stack deploy`.
- `to-yaml` template tag: renders context data as block-style YAML.
- nginx and redis example stacks under `example-stacks/`.
- More documentation in docs/docs.org; AGENTS.md / CLAUDE.md for coding agents.

### Changed
- The CLI is built on [lambdaisland/cli](https://github.com/lambdaisland/cli) instead of tools.cli: generated help for every command (`bb compoje deploy --help`), strict flag checking, `--[no-]with-registry-auth` (registry auth could not be turned off before), `--deploy-driver DRIVER` takes a value, and usage errors still exit 1. `--set` overrides are repeatable as before. The JVM can run `clojure -M -m compoje.cli`.
- YAML is parsed and generated with [yamlstar](https://github.com/yaml/yamlstar) (YAML 1.2) instead of clj-yaml (YAML 1.1). Values like `yes`, `no`, `on`, `off` now stay strings instead of becoming booleans. Known yamlstar 0.1.23 limitations: `to-yaml` adds a trailing newline to multi-line strings that lack one, and custom YAML tags are rejected.
- Configuration lives in the stack template itself, under a top-level `x-compoje:` key (docker, providers, secrets, values), and is read as YAML. The separate `compoje.edn` file is no longer used.
- Upgraded vault client from `amperity/vault-clj` 1.x to `com.amperity/vault-clj` 2.x.
- Dependency upgrades (all current as of 2026-11-21): Clojure 1.12.7, babashka/fs 0.5.34, babashka/process 0.6.25, cheshire 6.2.1, vault-clj 2.3.594, timbre 6.8.0, malli 0.20.3, selmer 1.13.5, tools.cli 1.4.256, tools.logging 1.3.2. Added malli; replaced clj-yaml with yamlstar 0.1.23.
- Dev/test dependency upgrades: testcontainers 2.0.5, slf4j 2.0.20, rich-comment-tests v1.0.5, portal 0.69.2, cljfmt 0.16.6 (fixes `bb fmt`, which failed under babashka on 0.13.0).
- Added antq (`clojure -M:outdated`) for dependency checks.

### Removed
- `luposlip/json-schema` dependency: unused, and it added JVM-only libraries to every install.

### Fixed
- Secret provider results were stored under string names, so templates could not reach them (`<{ name.key }>`); they are now keyword keys.
- `deploy` ran `docker stack deploy` without a stack name: the `x-compoje` docker settings and the `--stack`, `--context`, `--prune`, `--resolve-image` and `--with-registry-auth` flags never reached the docker command. They now do, with precedence defaults < x-compoje < `--set` < flags; a missing stack name is an error. `--prune` is now passed to docker.
- Secrets providers declared in `x-compoje` were dropped from the context, and a provider `type` from YAML (a string) did not match the registered provider. Both fixed.
- `deploy` with a bare file name (e.g. `bb compoje deploy stack.tpl.yml` run inside the stack directory) looked for the template at `/stack.tpl.yml`. A path without a directory part now means the current directory.
- `deploy` was rejected by Docker ("additional property 'compoje' is not allowed") because the config section stayed in the rendered stack. The key is now `x-compoje:`, a compose extension field. A legacy `compoje:` key is still read, with a deprecation warning.
- `deploy` crashed with a ClassCastException while building the context (`config->context` did not apply its extra maps to `deep-merge`).
- Integration tests work with Docker 29+: testcontainers pinned to 2.0.5 (the 1.17.6 version that clj-test-containers 0.7.4 pulls in is too old).

## [0.1] - 2023-08-18

### Added
- `deploy` command: renders a Selmer stack template (`<{ }>` / `<% %>` delimiters) and runs `docker stack deploy`.
- Content-hashed names for configs and secrets via the `file-hash` tag and `hash-file` filter, so changed files create new Swarm objects; `file-path` tag for template-relative paths.
- HashiCorp Vault secrets provider, which writes secrets as JSON (EDN is opt-in).
- Secrets and values exposed in the template context; `--dry-run` prints the context and the rendered stack.
- `--set path.to.key=value` CLI overrides, deep-merged over the file config.
- Kaocha test setup and cljfmt formatting via babashka.

[Unreleased]: https://github.com/ieugen/compoje/compare/0.1...HEAD
[0.1]: https://github.com/ieugen/compoje/releases/tag/0.1
