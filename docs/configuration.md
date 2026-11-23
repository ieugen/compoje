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
- **It stays in the output.** The rendered stack still contains `x-compoje:`. Docker ignores
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
| `driver` | `docker-api` | Deploy engine: `docker-api` uses the Docker API directly, without the docker CLI; `docker-cli` runs `docker stack deploy`. | `--deploy-driver` |
| `ssh-mode` | `dial-stdio` | `docker-api` with an `ssh://` endpoint: `dial-stdio` runs `docker system dial-stdio` on the remote host (like the docker CLI); `forward` forwards the remote socket instead (no remote docker binary needed). | none |
| `allow-insecure-tcp` | `false` | `docker-api`: allow plain `tcp://` without TLS (unauthenticated root on the host; avoid). | none |

The `docker-api` engine finds the daemon like the docker CLI does (`context`, `DOCKER_HOST`, `DOCKER_CONTEXT`, the
current context in `~/.docker/config.json`, the local socket) and reads your contexts from `~/.docker/contexts`, so
`ssh://` and TLS contexts keep working. It produces the same services, networks, secrets and configs as
`docker stack deploy` for the compose keys it supports. Unsupported keys are an error that lists them all; use
`docker-cli` for those stacks. See [engines.md](engines.md).

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

Any other key under `x-compoje:` is kept too, and is available in the template under its own name. compoje warns
about it (with a "did you mean" when it looks like a typo of `docker`, `providers`, `secrets` or `values`).

## Validation

`deploy` and `render` check the `x-compoje` section, after `--set`, before doing anything else. All problems are
reported at once:

```console
$ bb compoje deploy --dry-run stack.tpl.yml
[FATAL] Invalid x-compoje configuration in stack.tpl.yml:
  docker.prune: should be a boolean
  docker.stak: unknown key; did you mean stack?
  secrets[0].provider: unknown provider file (providers: files)
```

What is checked:

- `docker`: only the keys in the table above, with their types and allowed values.
- `providers`: `name` and `type` are required. For the built-in providers, only their own settings are allowed
  ([providers.md](providers.md)); custom provider types may have any keys.
- `secrets`: `name` and `provider` are required, and the provider must be declared. For built-in providers, the
  secret's settings are checked (e.g. `file` is required for the file and ansible-vault providers).
- Provider and secret names are unique.
- `--set` values are strings; where a boolean is expected, `true`/`false` are converted, so
  `--set docker.prune=false` really turns pruning off.

## Secrets and configs from memory

Docker reads secrets and configs from files: `file: secret.txt`. To avoid writing secret values to disk, give a
top-level secret or config definition an `x-compoje:` key instead. compoje then creates the object itself, from
memory:

```yaml
secrets:
  db_password:
    x-compoje:
      from: secrets.app.db_password        # a value from the context, e.g. a provider result
  tls_key:
    x-compoje:
      from: secrets.tls.key                # multi-line values (keys, certificates) work as they are
  app_config:
    x-compoje:
      from: secrets.app                    # a whole provider result (a map) ...
      format: edn                          # ... written as an EDN (or JSON) file

configs:
  app_conf:
    x-compoje:
      template: config/app.conf    # a file rendered with the context, like the stack file
```

| Key | Meaning |
|---|---|
| `from` | A dotted path in the [rendering context](#the-rendering-context): `secrets.app.db_password` is key `db_password` of secret `app`. Strings are used as they are; numbers and booleans become strings; maps and lists need `format`. |
| `format` | Optional, with `from`: `edn` (pretty-printed) or `json`. Writes the value in that format, whatever its type, e.g. a Vault secret's key/value map as an EDN config file. The same output as a provider's legacy `local-path` with `format`. |
| `template` | A template file, relative to the stack directory, rendered with the same context and syntax as the stack ([templates.md](templates.md)). Mostly for configs. |

Give exactly one of `from` and `template`, and don't combine `x-compoje` with `file` or `external`.

What compoje does on `deploy`:

1. Builds the content in memory.
2. Names the object: the definition's `name` if set, else
   `<stack>_<key>-<first 10 hex digits of the content's SHA-256>`, e.g.
   `ansible-vault-example_db_password-fb3ecca4eb`. Like docker stack's own `<stack>_<key>` names, so
   `docker secret ls` shows which stack and definition an object belongs to. Docker allows at most 64 characters;
   a longer generated name is an error, so set a shorter `name` then. Changing the value gives a new name, so the deploy succeeds even though Docker
   objects can't change.
3. Creates it unless an object with that name exists, with `docker secret|config create NAME -` (content on stdin)
   and the labels `com.docker.stack.namespace=<stack>` and `com.docker.stack.managed-by=compoje`, plus the
   definition's `labels`.
4. Passes the definition to docker as `{external: true, name: ...}`.

Services use these definitions like any others (`secrets: [db_password]`). `docker stack rm` also removes them,
because of the namespace label. Old versions stay behind after a redeploy, as with `file-hash` names; remove them
with `docker secret ls -q --filter label=com.docker.stack.managed-by=compoje | xargs docker secret rm` (in-use ones are
refused).

Things to know:

- The hash in the name is derived from the content. For short, guessable secrets (a 4-digit PIN), 40 bits of
  SHA-256 can be brute-forced. Set an explicit `name` (e.g. `db_password-v3`) and change it yourself when the value
  changes.
- `--dry-run` lists the objects it would create, with their sizes, never their content.
- A stack with such definitions is parsed after rendering and sent to docker as JSON. YAML 1.2 rules apply: write
  `true`/`false`, not `yes`/`no`.

### Template variables

Before rendering, `deploy` and `render` also check the variables used in `<{ }>` tags (in the stack file and in
`x-compoje: {template: ...}` files), because an undefined variable renders as an empty string:

- an undefined `secrets.<name>...` path, or a reference to a secret without `secrets.` (`<{ app.x }>`), is an
  error: an empty secret is never what you want;
- any other undefined variable is a warning. Add `|default:"..."` when an empty or default value is intended.

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
| `docker` | The merged docker options. |
| `providers` | The provider list from the config. |
| `secrets` | The provider results, one key per declared secret: `secrets.<name>` (e.g. `<{ secrets.app.db_user }>`). See [providers.md](providers.md#using-secrets-in-the-template). |
| `values` | Your values, after `--set`. |
| other `x-compoje` keys | As written. |
| `template-dir` | The stack file's directory, as given on the command line (may be relative, e.g. `.`). |
| `config-file` | The stack file. |
| `work-dir` | The directory compoje was started from. |

Run `bb compoje deploy --dry-run` to print the whole context. Be careful: it includes decrypted secrets.
