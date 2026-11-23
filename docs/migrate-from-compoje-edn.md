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
compoje.edn: config moved into the x-compoje section.
Migrated path/to/stack-dir/stack.tpl.yml. Review it and delete compoje.edn.
```

`migrate` adds the contents of `compoje.edn` to the top of `stack.tpl.yml` as an `x-compoje:` section:

- The rest of the template is copied as it is, including comments and `<{ }>` / `<% %>` tags.
- `compoje.edn` itself is left untouched.

It then converts secrets written to files into secrets created from memory; see
[Secrets written to files](#secrets-written-to-files). That step also runs on stacks that already have an
`x-compoje:` section and no `compoje.edn`, so `migrate` upgrades stacks migrated earlier too.

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

## Secrets written to files

Older stacks gave a secret to a service by writing it to a file with `local-path` and pointing a Docker secret's
`file:` at it. The file held the secret in plain text. compoje can now create Docker secrets from memory
([configuration.md](configuration.md#secrets-and-configs-from-memory)), and `migrate` converts the old form:

Before:

```yaml
x-compoje:
  secrets:
    - name: app
      provider: demo-vault
      file: secrets/app.vault.yml
      local-path: generated/db_password.txt
      spit-key: db_password

secrets:
  db_password:
    file: generated/db_password.txt
    name: db_password-<% file-hash 'generated/db_password.txt' %>
```

After:

```yaml
x-compoje:
  secrets:
    - name: app
      provider: demo-vault
      file: secrets/app.vault.yml

secrets:
  db_password:
    x-compoje:
      from: secrets.app.db_password
```

```console
$ bb compoje migrate stacks/app
Secret app: secrets.db_password now created from memory (x-compoje from: secrets.app.db_password); local-path removed.
Plaintext secret file stacks/app/generated/db_password.txt is no longer needed: delete it after a successful deploy.
Migrated stacks/app/stack.tpl.yml. Review it.
```

How it works:

- A provider secret with a `local-path` is converted when a top-level `secrets:` or `configs:` definition uses
  that path as its `file:`.
- The definition becomes `x-compoje: {from: secrets.<secret>.<spit-key>}`, or `{from: secrets.<secret>}` when the whole result is
  a string (file provider with a raw file, ansible-vault with `format: raw`).
- Without `spit-key`, a secret with `format: edn` or `format: json` wrote its whole result in that format. Its
  definition becomes `x-compoje: {from: secrets.<secret>, format: edn}` (or `json`), which creates the same content
  from memory. The secret keeps its `format`.
- `local-path` and `spit-key` are removed, along with comment lines directly above them.
- A `name:` built with `file-hash` is dropped: compoje names the object `<stack>_<key>-<hash>` itself. Any other
  `name:` and `labels:` are kept. Other keys are dropped and listed.
- The template is edited as text, so comments and layout stay. Each edit is checked by parsing the result; if it
  doesn't match, the edit is undone and reported.
- Old plaintext files are listed, never deleted. Delete them yourself once the new deploy works.

Secrets that can't be converted safely are left as they are, and the reason is printed:

| Reason | What to do |
|---|---|
| `spit-key` contains a dot (`cert.key`) | `from:` paths split on dots. Rename the key in the secret source (`cert_key`), or keep `local-path`. |
| No `spit-key`, no `format: edn`/`json`, and the result is a map | Add `spit-key`, or write `x-compoje: {from: secrets.<name>.<key>}` (one value) or `{from: secrets.<name>, format: edn}` (the whole map) by hand. |
| `local-path` is not used as a `file:` of a secret or config | It may feed something else (a bind mount, another tool); left as is. |
| The file is also used elsewhere in the template | E.g. a `file-path` tag or a volume. Edit by hand. |
| Could not locate the definitions in the text | Flow style (`secrets: {pw: {file: x}}`) or unusual layout. Edit by hand. |

Running `migrate` again is safe: converted secrets are left alone, and it reports "Nothing to migrate" when there is
nothing left to do.

## Stacks from compoje 0.3.0

Templates reference provider results as `secrets.<name>...` (`<{ secrets.cloud.API_KEY }>`), as compoje.edn stacks
always did. compoje 0.3.0 put them at the top level instead (`<{ cloud.API_KEY }>`, `from: cloud.API_KEY`).
`migrate` rewrites those references for the declared secret names, in the stack file and in files used with
`x-compoje: {template: ...}`:

```console
$ bb compoje migrate stacks/app
Template: 4 reference(s) to provider results now use secrets.<name>.
stacks/app/config/app.conf: 3 reference(s) to provider results now use secrets.<name>.
Migrated stacks/app/stack.tpl.yml. Review it.
```

A reference it misses doesn't go unnoticed: `deploy` and `render` stop with "provider results are under secrets;
write secrets.<name>...".

## Other errors

| Message | Meaning |
|---|---|
| `... already has an x-compoje section, and .../compoje.edn exists too.` | Both formats are present. Merge them by hand, or delete `compoje.edn`. |
| `Nothing to migrate: .../compoje.edn is missing and ... has no x-compoje section.` | Not a compoje stack directory. |
| `Missing .../stack.tpl.yml` | `migrate` needs the template in the directory. |
| `... is not valid YAML before rendering` | The template must parse as YAML (see [templates.md](templates.md#control-flow)). |
| `Not a directory: ...` | Pass the stack directory, not a file. |
