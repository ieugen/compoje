# Configuration reference

## The stack file

A compoje stack file is a [compose file](https://docs.docker.com/reference/compose-file/) with an extra top-level
`x-compoje:` section that holds compoje's settings:

```yaml
x-compoje:
  docker:      # how to deploy
    stack: web
  providers:   # where secrets come from
    - name: my-vault
      type: compoje.providers.ansible-vault/ansible-vault
      password-file: ~/.vault-pass
  secrets:     # which secrets to fetch
    - name: app
      provider: my-vault
      file: secrets/app.vault.yml
  values:      # your own values for the template
    replicas: 2

services:
  web:
    image: nginx:stable
    deploy:
      replicas: <{ values.replicas }>
```

Things to know:

- **The file must be valid YAML before rendering.** compoje reads `x-compoje:` from the file as it is on disk, so
  template tags must not break the YAML. `<{ ... }>` inside a value is fine. Put block tags such as `<% if %>` or
  `<% for %>` in YAML comments; see [templates.md](templates.md#control-flow).
- **The section is read as literal YAML.** Template tags inside `x-compoje:` are not rendered before the config is
  read. The section is rendered with the rest of the file, but that only affects the output.
- **YAML 1.2.** `yes`, `no`, `on` and `off` are strings, not booleans. Quote values that should stay strings,
  such as `'0.50'`.
- **It stays in the output.** The rendered `stack.generated.yml` still contains `x-compoje:`. Docker ignores
  top-level keys that start with `x-` ([extension fields](https://docs.docker.com/reference/compose-file/extension/)).
- **Legacy key.** A top-level `compoje:` key is still read, with a warning, but `docker stack deploy` rejects it.
  Rename it to `x-compoje:`. Old `compoje.edn` files are no longer read; convert them with
  [`compoje migrate`](migrate-from-compoje-edn.md).

## `x-compoje` keys

### `docker`

Options for `docker stack deploy`.

| Key | Default | Meaning | Flag |
|---|---|---|---|
| `stack` | none, required | Stack name. | `--stack` |
| `context` | current docker context | Docker context to deploy to. | `--context` |
| `prune` | `false` | Remove services no longer in the stack. | `--prune` |
| `resolve-image` | `always` | `always`, `changed` or `never`. | `--resolve-image` |
| `with-registry-auth` | `true` | Send registry credentials to the swarm agents. | `--[no-]with-registry-auth` |
| `compose-file` | `stack.generated.yml` next to the stack file | Where to write the rendered stack. A relative path is resolved against the current directory. | none |

### `providers`

A list of secret providers. Each entry has:

| Key | Meaning |
|---|---|
| `name` | Your name for this provider. Secrets refer to it. |
| `type` | Which provider implementation to use, e.g. `compoje.providers.ansible-vault/ansible-vault` or `compoje.providers.vault/vault`. |
| other keys | Provider settings, e.g. `password-file` or `addr`. |

The providers and their settings are documented in [providers.md](providers.md).

### `secrets`

A list of secrets to fetch. Each entry names a `provider` from the list above, plus settings for that provider.
The result is available in the template under the secret's `name`. See [providers.md](providers.md#secrets).

### `values`

Free-form data for the template: strings, numbers, lists, nested maps. Use them as `<{ values.some.key }>`.

```yaml
x-compoje:
  values:
    replicas: 2
    resources:
      limits:
        cpus: '0.50'
        memory: 50M
```

Any other key under `x-compoje:` is kept too, and is available in the template under its own name.

## Precedence

Settings are merged from these sources. Later ones win:

1. Built-in docker defaults (`resolve-image: always`, `with-registry-auth: true`)
2. The `x-compoje:` section of the stack file
3. `--set` overrides
4. Deploy flags (`--stack`, `--context`, `--prune`, `--resolve-image`, `--[no-]with-registry-auth`)

Maps are merged deeply: `--set values.a=1` changes `values.a` and keeps the other values. A flag only overrides
when you pass it.

## Overriding values with `--set`

```bash
bb compoje deploy --set values.replicas=3 --set docker.stack=web-test stacks/web/stack.tpl.yml
```

- The path before `=` is split on dots: `values.resources.limits.cpus=1` sets that nested key.
  Keys can't contain dots.
- Everything after the first `=` is the value. Surrounding quotes are removed: `--set "values.motd='hi there'"`.
- Values are always strings. `--set values.replicas=3` gives `"3"`, which renders the same in a template.
- `--set` can override anything in `x-compoje`, including `docker.*`, and it can add keys that aren't in the file.

## The rendering context

The template is rendered with a context map. It contains:

| Key | Content |
|---|---|
| `docker` | The merged docker options, plus `compose-file` (the absolute output path). |
| `providers` | The provider list from the config. |
| `secrets` | The secret declarations from the config (not their values). |
| `values` | Your values, after `--set`. |
| other `x-compoje` keys | As written. |
| `<secret name>` | One key per declared secret, holding what its provider returned. See [providers.md](providers.md#using-secrets-in-the-template). |
| `template-dir` | The stack file's directory, as given on the command line (may be relative, e.g. `.`). |
| `config-file` | The stack file. |
| `work-dir` | The directory compoje was started from. |

Run `bb compoje deploy --dry-run` to print the whole context. Be careful: it includes decrypted secrets.
