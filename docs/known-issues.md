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

## `--deploy-driver` option is malformed

Found 2026-11-21.

The global option in `main-opts` (src/compoje/cli.clj):

```clojure
["d" "--deploy-driver" "Use a deployment driver. Defaults to :docker"
 :default :docker]
```

- The short flag is `"d"`, not `"-d"`.
- The long option has no argument name (`--deploy-driver DRIVER`), so tools.cli treats it as a boolean flag
  that defaults to `:docker`.

Harmless while Docker is the only driver. Fix both when a second driver is added.

## yamlstar 0.1.23 limitations

These come from the YAML library, not compoje code, and are already documented in AGENTS.md and CHANGELOG.md:

- `dump` (used by the `to-yaml` template tag) adds a trailing newline to multi-line strings that lack one.
- Custom YAML tags (`!vault`, compose `!reset`/`!override`) throw "Unknown tag".

Revisit when a new yamlstar version is released.
