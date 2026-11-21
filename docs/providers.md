# Secret providers

Providers fetch secrets (or any other data) when you deploy, and make them available to the template. Three built-in
providers exist:

| Type | Source | Details |
|---|---|---|
| `compoje.providers.ansible-vault/ansible-vault` | Files encrypted with `ansible-vault`, whole or with inline `!vault` values. No Ansible or server needed. | [below](#ansible-vault), [ansible-vault.md](ansible-vault.md) |
| `compoje.providers.vault/vault` | [HashiCorp Vault](https://www.vaultproject.io/) KV version 2. | [below](#hashicorp-vault) |
| `compoje.providers.file/file` | Plain (unencrypted) YAML, JSON, EDN or text files. | [below](#file) |

You can also write your own; see [Custom providers](#custom-providers).

## How providers run

You declare providers and secrets in `x-compoje:`:

```yaml
x-compoje:
  providers:
    - name: team-vault                                      # your name for it
      type: compoje.providers.ansible-vault/ansible-vault   # which implementation
      password-file: ~/.vault-pass                          # provider settings
  secrets:
    - name: app                 # the result goes into the template context as "app"
      provider: team-vault      # a provider name from above
      file: secrets/app.yml     # settings for this secret
```

On every `deploy` (including `--dry-run`), compoje goes through `secrets` in order. For each secret it:

1. finds the provider with that `name`,
2. calls the provider's implementation (`type`) with the provider entry, the secret entry and the stack directory,
3. stores the result in the context under the secret's `name`.

Providers are only called for declared secrets. A provider without secrets does nothing.

## Secrets

Keys that every secret entry has:

| Key | Meaning |
|---|---|
| `name` | Name of the result in the template context. Required. Use a name that is not already a context key (`docker`, `values`, `secrets`, ...); see [known-issues.md](known-issues.md). |
| `provider` | The `name` of a provider. Required. |
| `local-path` | Optional. Also write the secret to this file, relative to the stack directory. Parent directories are created. Use it for Docker secrets, which are read from files. |
| `spit-key` | Optional, with `local-path`. Write only this key of the result, not all of it. |

The other keys depend on the provider.

### Using secrets in the template

Refer to the result by the secret's name:

```yaml
environment:
  DB_USER: <{ app.db_user }>
```

To give a secret to a service as a Docker secret, write it to a file with `local-path` and add a content hash to the
secret name, so changing the value creates a new Docker secret:

```yaml
x-compoje:
  secrets:
    - name: app
      provider: team-vault
      file: secrets/app.yml
      local-path: generated/db_password.txt
      spit-key: db_password

services:
  app:
    secrets:
      - source: db_password
        target: db_password

secrets:
  db_password:
    file: generated/db_password.txt
    name: db_password-<% file-hash 'generated/db_password.txt' %>
```

Files written with `local-path` contain the secret in plain text, and they are written on `--dry-run` too. Keep them
out of git, e.g. with a `generated/` entry in `.gitignore`.

## ansible-vault

Type `compoje.providers.ansible-vault/ansible-vault`. Reads files encrypted with `ansible-vault encrypt`, and YAML
files with inline `!vault` values. Pure Clojure, compatible with ansible-core, no Ansible needed.

Provider settings:

| Key | Meaning |
|---|---|
| `password-file` | File with the vault password, relative to the stack directory; `~` works. Whitespace around the password is removed, as Ansible does. |
| `password-env` | Alternative: name of an environment variable that holds the password. |
| `vault-id` | Optional label for the password (format 1.2 vaults). |

Without `password-file` and `password-env`, the file named by `$ANSIBLE_VAULT_PASSWORD_FILE` is used.

Secret settings:

| Key | Meaning |
|---|---|
| `file` | The encrypted file, relative to the stack directory. Required. |
| `format` | `yaml` (default): parse the decrypted YAML into a map, so `<{ app.db_user }>` works. `raw`: the decrypted text as one string. |

Full guide with examples: [ansible-vault.md](ansible-vault.md).

## HashiCorp Vault

Type `compoje.providers.vault/vault`. Reads secrets from a KV version 2 secrets engine.

```yaml
x-compoje:
  providers:
    - name: vault
      type: compoje.providers.vault/vault
      addr: https://vault.example.com:8200
  secrets:
    - name: nginx
      provider: vault
      mount-path: secret        # the KV v2 mount
      secret-path: web/nginx    # path of the secret in the mount
      local-path: generated/cert.key
      spit-key: cert.key
```

Provider settings (each has a fallback):

| Key | Fallback | Meaning |
|---|---|---|
| `addr` | `$VAULT_ADDR` | Vault server address. Required. |
| `token` | Java property `vault.token`, then `$VAULT_TOKEN`, then `~/.vault-token` (written by `vault login`) | Token authentication. |
| `app-role-id` | `$VAULT_APP_ROLE_ID` | AppRole authentication (role id). |
| `app-secret-id` | `$VAULT_APP_SECRET_ID` | AppRole authentication (secret id). |

AppRole is used when both its ids are set; otherwise the token. Keep tokens and secret ids out of the stack file and
use the environment variables or `~/.vault-token`.

Secret settings:

| Key | Meaning |
|---|---|
| `mount-path` | Mount path of the KV v2 engine, e.g. `secret`. |
| `secret-path` | Path of the secret in that mount. |
| `opts` | Optional read options passed to vault-clj's `read-secret`, e.g. `{version: 3}`. |
| `format` | With `local-path` and no `spit-key`: write the whole secret as `edn` or `json`. |

The result is the secret's key/value map: `<{ nginx.username }>`. Keys that contain dots, like `cert.key`, can't be
used in a template path; write them to a file with `spit-key` instead.

If a secret can't be read, compoje logs the error and exits with a non-zero code.

## File

Type `compoje.providers.file/file`. Reads plain, unencrypted files: values shared between stacks, or secrets that are
already on disk, e.g. written by CI or mounted by the host. The files are read as they are; for encrypted files use
[ansible-vault](#ansible-vault).

```yaml
x-compoje:
  providers:
    - name: files
      type: compoje.providers.file/file
  secrets:
    - name: common                    # <{ common.db.host }>
      provider: files
      file: shared/common.yml
    - name: api-token                 # copied for a docker secret
      provider: files
      file: secrets/api-token.txt
      local-path: generated/api-token.txt
```

Provider settings:

| Key | Meaning |
|---|---|
| `dir` | Optional. Directory the secrets' `file` paths are relative to. Itself relative to the stack directory; `~` works. Default: the stack directory. |

Secret settings:

| Key | Meaning |
|---|---|
| `file` | The file. Relative to `dir`; absolute and `~` paths are used as given. Required. |
| `format` | `yaml`, `json`, `edn` or `raw`. Default from the extension: `.yml`/`.yaml` → yaml, `.json` → json, `.edn` → edn, anything else → raw. |

yaml and json files give a map (`<{ common.db.host }>`), edn gives the data as written, raw gives the text as one
string (`<{ api-token }>`).

With `local-path` and no `spit-key`, the file is copied unchanged. With `spit-key`, only that value is written.

A runnable example: `example-stacks/file/` (`bb compoje deploy --dry-run example-stacks/file/stack.tpl.yml`).

## Custom providers

A provider is a Clojure record that implements `compoje.providers/SecretsProvider`:

- `(provider-name [this])`: returns the keyword used as `type`, e.g. `:my.env-provider/env`
  for `type: my.env-provider/env`.
- `(run [this {:keys [config secret-spec template-dir]}])`: `config` is the provider entry and `secret-spec` the
  secret entry, both maps with keyword keys; `template-dir` is the stack directory. Returns the value stored under
  the secret's name. Call `compoje.providers/secret->file!` to support `local-path` and `spit-key` like the built-in
  providers; it returns the secret spec's `:content`.

Example, a provider that reads environment variables:

```clojure
;; src/my/env_provider.clj
(ns my.env-provider
  (:require [compoje.providers :as providers]))

(defrecord EnvProvider []
  providers/SecretsProvider
  (provider-name [_] ::env)
  (run [_ {:keys [config secret-spec template-dir]}]
    (let [prefix (:prefix config "")
          content (into {}
                        (for [v (:vars secret-spec)]
                          [(keyword v) (System/getenv (str prefix v))]))]
      (providers/secret->file! template-dir (assoc secret-spec :content content)))))
```

Register it in your `bb.edn` task before calling compoje:

```clojure
{:paths ["src"]
 :deps {io.github.ieugen/compoje {:git/sha "<commit-sha>"}}
 :tasks {:requires ([compoje.cli :as cli]
                    [compoje.providers :as providers]
                    [my.env-provider :as env-provider])
         compoje {:doc "Run compoje."
                  :task (do (providers/register-provider! (env-provider/->EnvProvider))
                            (apply cli/main *command-line-args*))}}}
```

Use it in a stack file:

```yaml
x-compoje:
  providers:
    - name: env
      type: my.env-provider/env
      prefix: WEB_
  secrets:
    - name: web
      provider: env
      vars: [TOKEN]
services:
  web:
    environment:
      TOKEN: <{ web.TOKEN }>
```

`WEB_TOKEN=s3cret bb compoje deploy --dry-run web/stack.tpl.yml` renders `TOKEN: s3cret`.

The provider runs in Babashka, so it can only use libraries that work there.
