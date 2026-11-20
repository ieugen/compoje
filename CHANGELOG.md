# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- ansible-vault provider: decrypts ansible-vault encrypted files in pure Clojure (including PKCS#7 padding).
- `--with-registry-auth` deploy option, on by default and passed to `docker stack deploy`.
- `to-yaml` template tag: renders context data as block-style YAML.
- nginx and redis example stacks under `example-stacks/`.
- More documentation in docs/docs.org; AGENTS.md / CLAUDE.md for coding agents.

### Changed
- Configuration lives in the stack template itself, under a top-level `compoje:` key (docker, providers, secrets, values), and is read as YAML. The separate `compoje.edn` file is no longer used.
- Upgraded vault client from `amperity/vault-clj` 1.x to `com.amperity/vault-clj` 2.x.
- Dependency upgrades: Clojure 1.12, timbre 6.6, selmer 1.12.62, and others; added clj-yaml and malli.
- Added antq (`clojure -M:outdated`) for dependency checks.

### Fixed
- `deploy` crashed with a ClassCastException while building the context (`config->context` did not apply its extra maps to `deep-merge`).
- Integration tests work with Docker 29+: testcontainers pinned to 1.21.4 (the 1.17.6 version that clj-test-containers 0.7.4 pulls in is too old).

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
