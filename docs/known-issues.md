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

## Secret names can overwrite context keys

Found 2026-11-22.

Provider results are deep-merged into the top level of the render context under the secret's `name`
(`compoje.context/final-context`). A secret named `values`, `docker` or `secrets` merges into, or replaces, that
context key. Possible fixes: reject reserved names, or put results under their own key.

## `to-yaml` tag: no nested paths, no indentation

Found 2026-11-22.

`compoje.render/to-yaml` looks the argument up as a top-level context key only: `<% to-yaml values.resources %>`
renders `null`. Its output is not indented, so only the first line lines up when the tag is used inside a nested
block.

## `hash-file` filter resolves paths against the current directory

Found 2026-11-22.

The `file-hash` and `file-path` tags resolve paths against the stack directory. The `hash-file` filter
(`<{ values.path|hash-file }>`) slurps the path as given, so it resolves against the directory compoje was started
from. It also returns the full MD5, while `file-hash` returns 6 characters.

## Stack file must be valid YAML before rendering

Found 2026-11-22.

`config/file->config!` parses the raw stack file to read `x-compoje:`, so a Selmer block tag on its own line
(`<% if %>`) fails with "Parser finished before end of input". The workaround (tags inside YAML comments) is
documented in docs/templates.md. Possible fix: extract only the `x-compoje:` section before parsing.

## `deploy --help` prints "Positional arguments missing"

Found 2026-11-22.

`bb compoje deploy --help` prints `Positional arguments missing: <stack-file>` before the help text
(lambdaisland/cli behaviour).

## HashiCorp Vault provider exits the process

Found 2026-11-22.

`compoje.providers.vault` calls `System/exit -1` when a secret can't be read, or when there is no address, instead of
throwing. This skips the normal error reporting and makes it hard to test.

## yamlstar 0.1.23 limitations

These come from the YAML library, not compoje code, and are already documented in AGENTS.md and CHANGELOG.md:

- `dump` (used by the `to-yaml` template tag) adds a trailing newline to multi-line strings that lack one.
- Custom YAML tags (compose `!reset`/`!override`) throw "Unknown tag". `!vault` is handled by
  `ansible-vault.yamlstar`, which uses yamlstar's internal pipeline (parser/composer/resolver/constructor);
  re-check it when upgrading yamlstar.

Revisit when a new yamlstar version is released.
