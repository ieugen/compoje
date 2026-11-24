# Template reference

compoje renders the stack file with [Selmer](https://github.com/yogthos/Selmer), a Django/Jinja-like template
engine. The context it renders with is described in [configuration.md](configuration.md#the-rendering-context).

## Delimiters

compoje changes Selmer's delimiters so they don't clash with Docker's `{{ }}` service templates:

| Selmer default | compoje | Used for |
|---|---|---|
| `{{ x }}` | `<{ x }>` | values |
| `{% tag %}` | `<% tag %>` | tags (`if`, `for`, `file-hash`, ...) |

Docker's own templates pass through untouched:

```yaml
hostname: "{{.Node.Hostname}}-web"
```

Output is not HTML-escaped: `<{ x }>` writes the value as it is.

## Values

```yaml
replicas: <{ values.replicas }>
image: "registry.example.com/web:<{ values.tag }>"
password: <{ secrets.app.db_password|yaml-str }>   # a secret named "app", safely quoted
stack: <{ docker.stack }>
```

- Dots walk into maps: `<{ values.resources.limits.cpus }>`.
- A missing key renders as an empty string. It is not an error.
- Use YAML quotes when a value could change type or break the YAML, e.g. `"<{ values.tag }>"` for a tag like
  `1.10`.
- For values you don't control (secrets, provider results), use `|yaml-str`: it quotes and escapes the value, so
  `:`, `#`, quotes, newlines and `$` survive. See [Escaping values](#escaping-values).

## Filters

Every [Selmer filter](https://github.com/yogthos/Selmer#filters) is available, with `|`:

```yaml
stack-upper: <{ docker.stack|upper }>
log-level: <{ values.log-level|default:"info" }>
```

compoje adds the filter `hash-file`: `<{ values.path|hash-file }>` renders the full MD5 hex digest of the file
whose path is in the value. The path is relative to the **current directory**, not to the stack file. For config
names, prefer the `file-hash` tag.

### Escaping values

The rendered stack is still a compose file, so two things can change a value on its way to the service:

- **YAML.** A value with `: `, ` #`, quotes or a newline breaks the YAML, or turns into something else.
- **Variable substitution.** Docker (both engines) replaces `$VAR` and `${VAR}` in the stack with environment
  variables, so `pa$word` becomes `pa` plus the value of `$word` (usually nothing), and `${...}` forms it doesn't
  know are an error.

| Filter | Output for `pa$word: x#y "q"` | Use |
|---|---|---|
| `yaml-str` | `"pa$$word: x#y \"q\""` | Any value you don't control: write it unquoted, `KEY: <{ secrets.app.secret\|yaml-str }>`. Quotes and escapes for YAML, and doubles `$`. |
| `escape-dollar` | `pa$$word: x#y "q"` | Only doubles `$` (docker turns `$$` back into `$`). You do the YAML quoting, e.g. `'<{ x\|escape-dollar }>'` for values without `'`. |
| `yaml` | `"pa$$word: x#y \"q\""` | Maps and lists (and scalars): one line of flow YAML, every string escaped like `yaml-str`. See [Maps and lists](#maps-and-lists-yaml). |

```yaml
environment:
  DB_PASSWORD: <{ secrets.app.db_password|yaml-str }>
```

Both give the service the exact value with either engine. For secrets, prefer a Docker secret
([`x-compoje: {from: ...}`](configuration.md#secrets-and-configs-from-memory)): the value never goes through the
YAML, nor docker's substitution, nor `docker service inspect`.

### Maps and lists: `yaml`

`yaml` writes a whole map or list, e.g. a Vault secret, as one line of flow YAML (JSON syntax, which is valid
YAML), with `$` doubled in every string, keys included. Since it is one line, it fits at any indentation, and the
stack file stays valid YAML before rendering. Any dotted path works, so you can pick part of a secret:

```yaml
services:
  app:
    environment: <{ secrets.app-env|yaml }>     # the whole provider result
    labels: <{ secrets.app.labels|yaml }>       # one key of it
```

With a Vault secret `app-env` holding `{"DB_USER": "app", "DB_PASSWORD": "pa$word"}` this renders as:

```yaml
    environment: {"DB_USER":"app","DB_PASSWORD":"pa$$word"}
```

and the service gets `DB_PASSWORD=pa$word`. Environment values must be scalars, so a secret holding nested maps
fails in docker; point at the part that holds the variables. A runnable example (the file provider standing in
for Vault) is in [example-stacks/to-yaml/](../example-stacks/to-yaml/).

## compoje tags

Paths in these tags are relative to the stack file's directory. Quote them.

### `file-hash`

```yaml
configs:
  nginx_cfg:
    file: config/nginx.conf
    name: nginx_cfg-<% file-hash 'config/nginx.conf' %>
```

Renders the first 6 hex characters of the file's MD5 hash, e.g. `764efa`. Docker configs and secrets can't change,
so a name that changes with the content makes each change a new config, and the deploy succeeds. Old configs stay
behind; remove unused ones now and then:

```bash
docker config ls -q | xargs docker config rm   # in-use configs are refused and kept
docker secret ls -q | xargs docker secret rm
```

### `file-path`

```yaml
file: <% file-path 'config/nginx.conf' %>
```

Renders the file's absolute path.

### `to-yaml`

Writes a context entry as block-style YAML, for whole files such as config templates. In the stack file, use the
[`yaml` filter](#maps-and-lists-yaml) instead. The argument must be a **top-level** context key: `values`, `docker`
(or `secrets`, which writes every provider result: rarely what you want). `to-yaml values.resources` renders `null`.
Only the first line is indented, so put the tag at column 0, where the output is meant to start.

Use it in a config file rendered as a template
([`x-compoje: {template: ...}`](configuration.md#secrets-and-configs-from-memory)), for example to hand an
application its settings from `values`:

```yaml
# stack.tpl.yml
x-compoje:
  docker:
    stack: to-yaml-example
  values:
    server:
      port: 8080
      workers: 4
    features:
      - search
      - export

services:
  app:
    image: docker.io/nginx:stable
    configs:
      - source: app_settings
        target: /etc/app/settings.yml

configs:
  app_settings:
    x-compoje:
      template: config/settings.yml
```

```yaml
# config/settings.yml
<% to-yaml values %>
```

The config Docker gets:

```yaml
server:
  port: 8080
  workers: 4
features:
- search
- export
```

Each change to `values` changes the content, so compoje creates a new config. A runnable version is in
[example-stacks/to-yaml/](../example-stacks/to-yaml/):
`bb compoje deploy --dry-run example-stacks/to-yaml/stack.tpl.yml`.

Don't use `to-yaml` in the stack file itself; use the [`yaml` filter](#maps-and-lists-yaml). compoje reads the
stack file as YAML before rendering it (see [Control flow](#control-flow)). A tag at column 0 is not valid YAML
there, so the stack fails to load (`Parser finished before end of input`). Indented, or in a comment, only its
first output line lines up. Tags in YAML comments still run, so don't put `to-yaml` in a comment in the stack file
either.

## Control flow

Selmer's `if`, `for` and other [built-in tags](https://github.com/yogthos/Selmer#tags) work with `<% %>`. compoje
reads the stack file as YAML before rendering it, so a tag on its own line must not break the YAML. Put such tags
in a YAML comment. Selmer still runs them, and only an empty `#` line is left in the output:

```yaml
services:
  web:
    image: nginx:stable
    environment:
      # <% if values.debug %>
      DEBUG: "1"
      # <% endif %>
    ports:
      # <% for p in values.ports %>
      - "<{ p }>:<{ p }>"
      # <% endfor %>
```

With `values: {debug: true, ports: [80, 443]}` this renders:

```yaml
services:
  web:
    image: nginx:stable
    environment:
      #
      DEBUG: "1"
      #
    ports:
      #
      - "80:80"
      #
      - "443:443"
      #
```

`deploy` and `render` check that the rendered stack is valid YAML and fail if it isn't (`render` prints it first, so
you can see why). To also check it as a compose file, run
`bb compoje render stack.tpl.yml | docker stack config -c -` in the stack's directory.
