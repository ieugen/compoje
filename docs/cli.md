# Command line reference

compoje runs as a Babashka task, usually named `compoje` in your `bb.edn` (see [quick-start.md](quick-start.md#install)):

```bash
bb compoje [global flags] <command> [command flags] <argument>
```

Commands: [`deploy`](#deploy) and [`migrate`](#migrate).

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
4. Writes the rendered stack to `stack.generated.yml` next to the stack file (or to `docker.compose-file`).
5. Runs `docker stack deploy`.

| Flag | Meaning |
|---|---|
| `--stack NAME` | Stack name. Overrides `docker.stack`. A stack name is required. |
| `--context NAME` | [Docker context](https://docs.docker.com/engine/context/working-with-contexts/) to deploy to, e.g. a remote swarm (`docker --context NAME`). |
| `--prune` | Remove services that are no longer in the stack. |
| `--resolve-image STRATEGY` | `always` (default), `changed` or `never`. Passed to `docker stack deploy`. |
| `--[no-]with-registry-auth` | Send registry credentials to the swarm agents. On by default. |
| `--dry-run` | Render and print the docker command, the context and the stack. Deploys nothing and writes no stack file. |
| `-s`, `--set PATH=VALUE` | Override a config value, e.g. `--set values.replicas=2`. Repeatable. See [configuration.md](configuration.md#overriding-values-with---set). |

The docker command it runs looks like this:

```text
docker [--context CTX] stack deploy [--with-registry-auth] --resolve-image always [--prune] --compose-file /abs/path/stack.generated.yml STACK
```

Notes:

- The rendered template is always `stack.tpl.yml` in the stack file's directory, whatever file name you pass
  ([known issue](known-issues.md#deploy-always-renders-stacktplyml)). Keep the name `stack.tpl.yml`.
- `--dry-run` still runs the providers, and providers with `local-path` still write their files
  ([known issue](known-issues.md#provider-local-path-files-are-written-on---dry-run)).
- `--dry-run` prints the full context, which includes decrypted secrets.

Examples:

```bash
bb compoje deploy --dry-run stacks/web/stack.tpl.yml
bb compoje deploy --context prod --prune stacks/web/stack.tpl.yml
bb compoje deploy --stack web-test --set values.replicas=1 stacks/web/stack.tpl.yml
bb compoje -v deploy --dry-run stacks/web/stack.tpl.yml
```

## migrate

```bash
bb compoje migrate [--dry-run] <dir>
```

Converts a stack directory in the old layout (`stack.tpl.yml` + `compoje.edn`): the contents of `compoje.edn`
become an `x-compoje:` section at the top of `stack.tpl.yml`. `compoje.edn` is left in place. Stops when
`compoje.edn` uses aero tags (`#env`, `#or`, ...) or the template is already migrated.

| Flag | Meaning |
|---|---|
| `--dry-run` | Print the migrated `stack.tpl.yml`. Do not write it. |

Details in [migrate-from-compoje-edn.md](migrate-from-compoje-edn.md).

## Exit codes and errors

| Exit code | When |
|---|---|
| 0 | The command finished, or help was shown. |
| 1 | Usage errors (no command, unknown command, missing argument; help is printed), unknown flags, and errors while running, printed as `[FATAL] message` followed by details. |
| other | A failing `docker stack deploy` exits 1; the HashiCorp Vault provider exits -1 (255) when it can't read a secret. |

Common messages are listed in [quick-start.md](quick-start.md#troubleshooting).
