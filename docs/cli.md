# Command line reference

compoje runs as a Babashka task, usually named `compoje` in your `bb.edn` (see [quick-start.md](quick-start.md#install)):

```bash
bb compoje [global flags] <command> [command flags] <argument>
```

Commands: [`deploy`](#deploy), [`render`](#render) and [`migrate`](#migrate).

`bb compoje --help` and `bb compoje <command> --help` print the built-in help.

## Global flags

| Flag | Meaning |
|---|---|
| `-v`, `--verbose` | More logging. `-v` is debug, `-vv` trace. Trace output can include secret values. |
| `--deploy-driver DRIVER` | How to deploy. Only `docker` exists (the default). |
| `-h`, `--help` | Show help. |

Global flags can go before or after the command. Command flags must go after the command, otherwise the
command's flags are unknown: `bb compoje --dry-run deploy ...` fails with `Unknown flag: --dry-run`.

## deploy

```bash
bb compoje deploy [flags] <stack-file>
```

Deploys a stack file (a compose file with an `x-compoje:` section, see [configuration.md](configuration.md)):

1. Reads the `x-compoje:` section of `<stack-file>`, applies `--set` overrides and the docker flags below.
2. Runs the secret providers for every declared secret ([providers.md](providers.md)).
3. Renders the template with the result ([templates.md](templates.md)).
4. Creates the [in-memory secrets and configs](configuration.md#secrets-and-configs-from-memory) that don't exist
   yet.
5. Runs `docker stack deploy` with the rendered stack on stdin, from the stack file's directory (so relative
   `file:` paths work). The rendered stack is not written to disk; use [`render`](#render) to see or save it.

| Flag | Meaning |
|---|---|
| `--stack NAME` | Stack name. Overrides `docker.stack`. A stack name is required. |
| `--context NAME` | [Docker context](https://docs.docker.com/engine/context/working-with-contexts/) to deploy to, e.g. a remote swarm (`docker --context NAME`). |
| `--prune` | Remove services that are no longer in the stack. |
| `--resolve-image STRATEGY` | `always` (default), `changed` or `never`. Passed to `docker stack deploy`. |
| `--[no-]with-registry-auth` | Send registry credentials to the swarm agents. On by default. |
| `--dry-run` | Print the docker command and the context (on stderr, provider results shown as `<redacted>`), and the rendered stack (on stdout). Deploys nothing. |
| `--show-secrets` | With `--dry-run`: show the provider results in the printed context. |
| `-s`, `--set PATH=VALUE` | Override a config value, e.g. `--set values.replicas=2`. Repeatable. See [configuration.md](configuration.md#overriding-values-with---set). |

The docker command it runs looks like this:

```text
docker [--context CTX] stack deploy [--with-registry-auth] --resolve-image always [--prune] --compose-file - STACK
```

Notes:

- The rendered template is always `stack.tpl.yml` in the stack file's directory, whatever file name you pass
  ([known issue](known-issues.md#deploy-always-renders-stacktplyml)). Keep the name `stack.tpl.yml`.
- `--dry-run` still runs the providers, and providers with the legacy `local-path` still write their files
  ([known issue](known-issues.md#provider-local-path-files-are-written-on---dry-run)). It lists the in-memory
  secrets and configs it would create, without their content.
- The rendered stack (stdout of `--dry-run`) contains every value the template uses, secrets included.
- Logs go to stderr and data to stdout, so `bb compoje deploy --dry-run x.yml > out.yml` saves just the stack.

Examples:

```bash
bb compoje deploy --dry-run stacks/web/stack.tpl.yml
bb compoje deploy --context prod --prune stacks/web/stack.tpl.yml
bb compoje deploy --stack web-test --set values.replicas=1 stacks/web/stack.tpl.yml
bb compoje -v deploy --dry-run stacks/web/stack.tpl.yml
```

## render

```bash
bb compoje render [flags] <stack-file>
```

Runs the secret providers and renders the stack like `deploy` does, then prints it to stdout. Logs go to stderr.
Use it to debug a template, or to pipe the stack to another tool:

```bash
bb compoje render stacks/web/stack.tpl.yml | less
bb compoje render stacks/web/stack.tpl.yml | (cd stacks/web && docker stack config -c -)
bb compoje render -o /tmp/web.yml stacks/web/stack.tpl.yml
```

| Flag | Meaning |
|---|---|
| `-o`, `--output FILE` | Write the stack to `FILE` instead of stdout (`-` means stdout). |
| `--stack NAME` | Stack name, as for `deploy`. |
| `-s`, `--set PATH=VALUE` | Override a config value, as for `deploy`. |

The output contains the values the template uses, secrets included. Don't commit it, and remove files written
with `--output` when you are done.

## migrate

```bash
bb compoje migrate [--dry-run] <dir>
```

Upgrades a stack directory to the current format. Two steps, each when it applies:

1. **`compoje.edn` → `x-compoje:`**: the contents of `compoje.edn` become an `x-compoje:` section at the top of
   `stack.tpl.yml`. `compoje.edn` is left in place. Stops when `compoje.edn` uses aero tags (`#env`, `#or`, ...).
2. **Secrets written to files → secrets from memory**: definitions that read a provider's `local-path` file become
   `x-compoje: {from: ...}`, so secrets are no longer written to disk. Secrets that can't be converted safely are
   left as they are and reported.

| Flag | Meaning |
|---|---|
| `--dry-run` | Print the migrated `stack.tpl.yml` (stdout) and the report (stderr). Do not write it. |

Details in [migrate-from-compoje-edn.md](migrate-from-compoje-edn.md).

## Exit codes and errors

| Exit code | When |
|---|---|
| 0 | The command finished, or help was shown. |
| 1 | Usage errors (no command, unknown command, missing argument; help is printed), unknown flags, and errors while running, printed as `[FATAL] message` followed by details. |
| other | A failing `docker stack deploy` exits 1; the HashiCorp Vault provider exits -1 (255) when it can't read a secret. |

Common messages are listed in [quick-start.md](quick-start.md#troubleshooting).
