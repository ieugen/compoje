# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
