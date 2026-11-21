# Migrating from compoje.edn

Older versions of compoje kept a stack in two files in the same directory:

- `stack.tpl.yml`: the compose template
- `compoje.edn`: compoje's settings (docker, providers, secrets, values), read with [aero](https://github.com/juxt/aero)

Today the settings live inside the template, in an `x-compoje:` section. compoje no longer reads `compoje.edn`, and
support for it is being removed. The `migrate` command converts an old stack directory in one step.

## Run it

Preview the result first. `--dry-run` prints the new `stack.tpl.yml` and writes nothing:

```bash
bb compoje migrate --dry-run path/to/stack-dir
```

Then migrate:

```console
$ bb compoje migrate path/to/stack-dir
Migrated path/to/stack-dir/stack.tpl.yml - review it, then delete path/to/stack-dir/compoje.edn
```

`migrate` adds the contents of `compoje.edn` to the top of `stack.tpl.yml` as an `x-compoje:` section:

- The rest of the template is copied as it is, including comments and `<{ }>` / `<% %>` tags.
- `compoje.edn` itself is left untouched.

Check the result with a dry-run deploy, then delete `compoje.edn`:

```bash
bb compoje deploy --dry-run path/to/stack-dir/stack.tpl.yml
rm path/to/stack-dir/compoje.edn
```

## Before and after

`compoje.edn`:

```clojure
{:docker {:stack "nginx"}
 :providers [{:name :my-vault
              :type :compoje.providers.vault/vault
              :addr "http://localhost:8200"}]
 :secrets [{:name "cert"
            :provider :my-vault
            :local-path "nginx/cert.crt"
            :spit-key :cert.crt
            :mount-path "secret"
            :secret-path "nginx"}]
 :values {:resource-reservations-mem "128m"}}
```

The top of `stack.tpl.yml` after `migrate`:

```yaml
x-compoje:
  docker:
    stack: nginx
  providers:
  - name: my-vault
    type: compoje.providers.vault/vault
    addr: 'http://localhost:8200'
  secrets:
  - name: cert
    provider: my-vault
    local-path: nginx/cert.crt
    spit-key: cert.crt
    mount-path: secret
    secret-path: nginx
  values:
    resource-reservations-mem: 128m

version: '3.9'

services:
  ...
```

Keywords become strings (`:my-vault` → `my-vault`, `:compoje.providers.vault/vault` →
`compoje.providers.vault/vault`). Sets become lists. Numbers, booleans and strings keep their type, so `"4"` stays
the string `'4'`.

## Aero tags

`compoje.edn` was read with aero, so it could compute values with tags such as `#env`, `#or`, `#profile` or
`#include`. The `x-compoje:` section is plain YAML and has no such tags. `migrate` doesn't guess. It also never
writes values from your environment into the file, since they may be secrets. Instead it stops and lists every
tag:

```console
$ bb compoje migrate stacks/nginx
[FATAL] compoje.edn uses aero tags, which x-compoje cannot express:
  [:docker :stack] #profile {:dev "nginx-dev", :prod "nginx"}
  [:providers 0 :addr] #or [#env "VAULT_ADDR" "http://localhost:8200"]
Replace each one with a literal value in compoje.edn (and use --set, or a secret
provider, for values that differ per environment), then run migrate again.
```

Replace each tag in `compoje.edn`, then run `migrate` again:

- **Same value everywhere**: write the value itself.
- **Value differs per environment** (`#profile`, `#env`): put a default in the file and override it at deploy
  time, e.g. `bb compoje deploy --set docker.stack=nginx-dev ...` or `--stack nginx-dev`.
- **Secrets**: fetch them with a secret provider (HashiCorp Vault or [ansible-vault](ansible-vault.md)) instead of
  environment variables.

## Other errors

| Message | Meaning |
|---|---|
| `... already has an x-compoje section. Nothing to migrate.` | The template is already migrated (or has a legacy `compoje:` key). |
| `Missing .../compoje.edn` or `Missing .../stack.tpl.yml` | `migrate` needs both files in the directory. |
| `Not a directory: ...` | Pass the stack directory, not a file. |
