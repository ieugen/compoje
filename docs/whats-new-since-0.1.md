# What changed since compoje 0.1

A summary for users of compoje 0.1 (2023) moving to 0.7.0. It covers everything that changed in 0.2.0 through
0.7.0, grouped by topic. [CHANGELOG.md](../CHANGELOG.md) has the full list, release by release.

## In short

- **Configuration lives in the stack file.** `compoje.edn` is replaced by an `x-compoje:` section in
  `stack.tpl.yml`. `compoje migrate` converts old stacks.
- **Secrets don't need to touch the disk.** compoje can create Docker secrets and configs from memory. Writing
  provider results to files with `local-path` is legacy, and `migrate` converts most of it.
- **No docker CLI needed.** The default deploy engine talks to the Docker API directly, locally or on remote swarms
  over SSH or TLS. `docker stack deploy` is still available as the `docker-cli` engine.
- **More secret providers.** ansible-vault and plain files, next to HashiCorp Vault.
- **Easier to install and use.** Release jar, one-line installer, bbin, new commands (`init`, `render`,
  `migrate`, `completion`), config validation, and documentation under [docs/](README.md).

## Upgrading a 0.1 stack

1. **Install 0.7.0**, see [install.md](install.md). For a bb task, pin the tag in your bb.edn:
   `{:git/tag "v0.6.1" :git/sha "9bd628eff9d579bc40119016d179f02026fe4ee7"}`.
2. **Remove aero tags from compoje.edn.** `#env`, `#or`, `#profile` and the like have no equivalent in `x-compoje`.
   Replace them with literal values. For values that change per environment, use `--set` or a secret provider.
3. **Run `bb compoje migrate --dry-run <dir>`**, check the output, then run it without `--dry-run`. It:
   - moves compoje.edn into an `x-compoje:` section at the top of `stack.tpl.yml` (comments and template tags
     stay as they are; compoje.edn itself is left in place)
   - turns secrets written with `local-path` and used as a secret's or config's `file:` into secrets created from
     memory (`x-compoje: {from: secrets.<name>.<key>}`, with `key:` for keys like `grafana.ini`), removes a
     `local-path` nothing uses, names secrets that had no name, and reports the ones it can't convert safely
   - validates the result like `deploy` does (exit status 1 if it isn't valid); a template that isn't valid YAML
     still gets its `x-compoje` section, with a warning that the secrets weren't migrated

   See [migrate-from-compoje-edn.md](migrate-from-compoje-edn.md).
4. **Delete compoje.edn** and the old plaintext secret files (e.g. `generated/`) once the new deploy works.
   `migrate` lists those files but never deletes them.
5. **Check the stack:** `bb compoje deploy --dry-run <dir>/stack.tpl.yml`. With the default engine the dry run
   connects to the swarm and lists what it would create or update. If the stack uses compose keys the Docker API
   engine doesn't support, compoje names the key. Then either change the stack or keep `docker stack deploy`:
   ```yaml
   x-compoje:
     docker:
       driver: docker-cli
   ```

## Breaking changes

| Change | Since | What to do |
|---|---|---|
| Config moved from `compoje.edn` to `x-compoje:` in the stack file, read as YAML | 0.2.0 | Run `compoje migrate`. |
| The section key is `x-compoje:`; `compoje:` (which Docker rejected) is still read, with a warning | 0.2.0 | Rename it, or let `migrate` do it. |
| YAML 1.2: `yes`, `no`, `on`, `off` stay strings, not booleans | 0.2.0 | Write `true` / `false`. |
| Strict CLI flags (lambdaisland/cli); unknown flags are an error, `--deploy-driver` takes a value | 0.2.0 | Check scripts that call compoje. `--help` works on every command. |
| `deploy` no longer writes `stack.generated.yml`; the rendered stack goes to docker on stdin | 0.3.0 | Use `compoje render [-o FILE]` to look at the result. `docker.compose-file` is ignored. |
| Undefined `secrets.` paths in templates fail `deploy` and `render` (other undefined variables warn) | 0.4.0 | Fix the reference, or use `\|default`. To deploy anyway while fixing the data: `--allow-undefined` or `render.undefined: warn` (0.7.0). |
| Default engine is `docker-api`, not `docker stack deploy`; its `--dry-run` reads from the swarm | 0.5.0 | Set `docker.driver: docker-cli` or pass `--deploy-driver docker-cli` where needed. |
| `prune` is on by default: services of the stack that are no longer in the file are removed | 0.6.0 | Pass `--no-prune` or set `docker.prune: false` to keep them. |

Provider results are under `secrets` in templates (`<{ secrets.<name>.<key> }>`), as in 0.1. 0.3.0 briefly moved
them to the top level; `migrate` rewrites stacks written for 0.3.0.

## New features

### Stack file and templates

- One file per stack: compose plus `x-compoje:` (`docker`, `providers`, `secrets`, `values`). The section stays in
  the rendered stack; the `x-` prefix makes Docker accept it. See [configuration.md](configuration.md).
- Validation of `x-compoje`: unknown or mistyped keys (with "did you mean"), allowed values, required provider
  settings, duplicate names. All problems are reported at once.
- `--set` booleans work: `--set docker.prune=false` really turns pruning off.
- Template additions: the `to-yaml` tag, and the `yaml-str` and `escape-dollar` filters, so secret values survive
  YAML quoting and docker's `$` substitution. See [templates.md](templates.md).

### Secrets

- Docker secrets and configs from memory: a top-level definition with `x-compoje: {from: <context path>}` or
  `x-compoje: {template: <file>}` is created by compoje and given to docker as external. The name is
  `<stack>_<key>-<content hash>` unless you set one, so a changed value creates a new object, like `file-hash` did.
  `format: edn|json` writes a whole map, e.g. a Vault secret as an EDN config file; `key:` picks an entry whose name
  has dots (`grafana.ini`).
- New providers: **ansible-vault** (whole encrypted files and inline `!vault` values, in pure Clojure, compatible
  with ansible-core) and **file** (plain YAML, JSON, EDN or text). See [providers.md](providers.md) and
  [ansible-vault.md](ansible-vault.md).
- Vault provider: vault-clj 2.x, and errors are reported instead of exiting the process.
- `deploy --dry-run` redacts provider results (`--show-secrets` shows them) and writes no files, not even legacy
  `local-path` ones. Logs go to stderr, data to stdout.
- [security.md](security.md) lists where secret values can end up, with a checklist.

### Deploying

- Two engines, see [engines.md](engines.md):
  - `docker-api` (default): deploys through the Docker API without the docker CLI. It finds the daemon like the
    CLI does (`--context`, `DOCKER_HOST`, docker contexts) and reaches remote swarms over `ssh://` or `tcp://`
    with mutual TLS. It supports `--prune`, `--resolve-image` and `--with-registry-auth` (including credential
    helpers), and does `${VAR}` substitution. Its results match `docker stack deploy` for the supported compose
    keys, which the integration tests check.
  - `docker-cli`: runs `docker stack deploy`, with every compose key Docker supports.
- The docker settings (`stack`, `context`, `prune`, `resolve-image`, `with-registry-auth`) now reach the deploy.
  Precedence: defaults < `x-compoje` < `--set` < flags. In 0.1 they were dropped. `deploy` logs the engine and
  the options in effect before it deploys.

### Commands

| Command | What it does |
|---|---|
| `deploy` | Render and deploy, as before; `--dry-run`, `--show-secrets`, engine and docker flags |
| `render [-o FILE]` | Print or save the rendered stack |
| `init [--stack NAME] <dir>` | Create a new stack directory from a template |
| `migrate [--dry-run] <dir>` | Upgrade old stacks (see above) |
| `completion bash\|zsh` | Shell completion for commands, flags, contexts and `--set` paths |
| `--version` | Print the version |

See [cli.md](cli.md).

### Installing

- Releases on GitHub with a self-contained `compoje.jar` that needs only Babashka.
- One-line installer (checksum-verified, adds bash completion), bbin, or a bb task pinned to a tag. See
  [install.md](install.md).
- A [quick start guide](quick-start.md) and reference documentation under [docs/](README.md).

## Fixed bugs worth knowing about

These affected 0.1 stacks:

- The `x-compoje` docker settings and the `--stack`, `--context`, `--prune`, `--resolve-image` flags never
  reached `docker stack deploy`.
- Docker rejected the deploy because of the `compoje:` key left in the rendered stack.
- Provider results were stored under string keys, so templates couldn't reach them.
- `deploy stack.tpl.yml` (a bare file name) looked for the template in `/`, and `deploy` used `stack.tpl.yml`
  next to the given file instead of the file itself.
- Undefined template variables rendered as empty strings without a warning.
