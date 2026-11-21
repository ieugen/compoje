# Known issues

Open problems that were found but deliberately left for later review.
When one is fixed, remove it here and add a line to CHANGELOG.md under `[Unreleased] → Fixed`.

## `deploy` always renders `stack.tpl.yml`

Found 2026-11-21.

`compoje.cli/deploy` (src/compoje/cli.clj) reads the `x-compoje:` config from the file given on the command line,
but always renders `<template-dir>/stack.tpl.yml`:

```clojure
template-file (str (fs/file template-dir "stack.tpl.yml"))
```

So `bb compoje deploy path/to/other.yml` loads `other.yml`'s config but renders the sibling `stack.tpl.yml`,
or fails if there is none.

Likely fix: render the file that was passed, since config and template now live in the same file.

## Provider `local-path` files are written on `--dry-run`

Found 2026-11-21.

`compoje.providers/secret->file!` writes decrypted secrets to `local-path` on every run, including `--dry-run`
(there is a TODO in the code). Both the vault and ansible-vault providers use it.

## `example01.yml` fixture has no known password

Found 2026-11-21.

`test-resources/ansible_vault/example01.yml` (inline `!vault`, label `dev`) does not decrypt with
`password01.txt`, and its password is not in the repository. No test uses it. Add its password file or remove it.

## yamlstar 0.1.23 limitations

These come from the YAML library, not compoje code, and are already documented in AGENTS.md and CHANGELOG.md:

- `dump` (used by the `to-yaml` template tag) adds a trailing newline to multi-line strings that lack one.
- Custom YAML tags (compose `!reset`/`!override`) throw "Unknown tag". `!vault` is handled by
  `ansible-vault.yamlstar`, which uses yamlstar's internal pipeline (parser/composer/resolver/constructor);
  re-check it when upgrading yamlstar.

Revisit when a new yamlstar version is released.
