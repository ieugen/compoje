# ansible-vault secrets

compoje can read secrets encrypted with [ansible-vault](https://docs.ansible.com/ansible/latest/vault_guide/index.html),
in both forms Ansible produces:

- **whole encrypted files**, made with `ansible-vault encrypt` or `ansible-vault create`
- **YAML with inline encrypted values** (`key: !vault |`), made with `ansible-vault encrypt_string`

No Ansible or Python is needed at runtime. The vault format is implemented in Clojure and runs in Babashka.

## Using it in a stack

Declare a provider and the secrets it decrypts in the `x-compoje` section of the stack file.
`example-stacks/ansible-vault/` is a complete, runnable example:

```bash
bb compoje deploy --dry-run example-stacks/ansible-vault/stack.tpl.yml
```

```yaml
x-compoje:
  docker:
    stack: my-stack
  providers:
    - name: my-vault
      type: compoje.providers.ansible-vault/ansible-vault
      password-file: vault-password.txt   # see "Password" below
  secrets:
    # whole file encrypted with `ansible-vault encrypt`
    - name: app
      provider: my-vault
      file: secrets/app.vault.yml
    # plain YAML with inline `!vault` values
    - name: tokens
      provider: my-vault
      file: secrets/tokens.yml

services:
  app:
    environment:
      DB_USER: <{ secrets.app.db_user }>
      API_TOKEN: <{ secrets.tokens.api_token }>
    secrets:
      - source: db_password
        target: db_password

secrets:
  db_password:
    x-compoje:
      from: secrets.app.db_password   # Docker secret created by compoje from memory
```

Each secret's decrypted content is available in the template under the secret's `name`
(`<{ secrets.app.db_user }>` above). `x-compoje.from` turns a value into a Docker secret without writing it to disk; see
[configuration.md](configuration.md#secrets-and-configs-from-memory).

### Provider options

| Key | Description |
|---|---|
| `type` | `compoje.providers.ansible-vault/ansible-vault` |
| `password-file` | File holding the vault password. Relative paths are relative to the stack file's directory; `~` is expanded. Surrounding whitespace is stripped, as ansible does. |
| `password-env` | Name of an environment variable holding the password. Used when `password-file` is not set. |
| `vault-id` | Optional label for the password. Vaults whose header has the same label (`$ANSIBLE_VAULT;1.2;AES256;<label>`) try this password first. |

If neither `password-file` nor `password-env` is set, `$ANSIBLE_VAULT_PASSWORD_FILE` is used, like ansible does.
An empty or missing password is an error.

### Secret options

| Key | Description |
|---|---|
| `name` | Name of the secret in the template context. |
| `provider` | Name of an ansible-vault provider. |
| `file` | The file to read, relative to the stack file's directory. A whole-file vault is decrypted first. |
| `format` | `yaml` (default): parse the content as YAML, decrypting inline `!vault` values. `raw`: keep the decrypted text as a string. |
| `local-path` | Legacy, optional. Write the decrypted content to this file (relative to the stack directory). Prefer `x-compoje.from`. |
| `spit-key` | Optional, with `local-path`. Write only this key of the YAML content. |

### Security notes

- Docker secrets declared with `x-compoje.from` never touch the disk.
- Legacy `local-path` files contain decrypted secrets. They are written on every `deploy` and `render` (not on `deploy --dry-run`).
  Keep them out of version control (the example's `.gitignore` excludes `generated/`).
- Values used in the template end up decrypted in the rendered stack. compoje passes it to docker on stdin and
  doesn't write it to disk, but `render` and `deploy --dry-run` print it. In the context printed by `--dry-run`,
  provider results are redacted unless you pass `--show-secrets`.
- Never commit a real vault password. `example-stacks/ansible-vault/vault-password.txt` is a demo password.

## The library

The vault format lives in namespaces with no compoje dependencies, ready to be extracted as a library:

| Namespace | Depends on | Purpose |
|---|---|---|
| `ansible-vault.core` | JDK, `ansible-vault.crypto` | Public API: `encrypted?`, `decrypt`, `decrypt-bytes`, `decrypt-and-get-vault-id`, `encrypt`, `parse-envelope`, `format-envelope`, `parse-vaulttext`. |
| `ansible-vault.crypto` | JDK | PBKDF2/AES-CTR/HMAC primitives, hex, PKCS7, constant-time compare. |
| `ansible-vault.yamlstar` | yamlstar | Optional: `load-yaml` decrypts inline `!vault` values. Uses yamlstar's internal pipeline (0.1.23). |

```clojure
(require '[ansible-vault.core :as av])

(av/encrypt "db_password: s3cret\n" "my-password")                   ; format 1.1
(av/encrypt "s3cret" "my-password" {:vault-id "dev"})                ; format 1.2;dev
(av/decrypt vault-text "my-password")                                 ; => plaintext string
(av/decrypt vault-text [["dev" "dev-pass"] ["prod" "prod-pass"]])     ; several passwords
(av/decrypt-and-get-vault-id vault-text {"dev" "dev-pass"})           ; => {:plaintext bytes :vault-id "dev"}
```

Supported: vault format 1.1 and 1.2 (reading and writing), cipher AES256, UTF-8 passwords, binary plaintext,
CRLF line endings. Format 1.0 (cipher `AES`, deprecated by Ansible) is not supported.
Errors are `ex-info` with a `:type` such as `:ansible-vault/no-matching-secret` (wrong password) or
`:ansible-vault/format-error`; see the `ansible-vault.core` docstring.

### Compatibility testing

Output is checked against the reference implementation, ansible-core, running in a container
(`test-resources/ansible_vault/Dockerfile`, via testcontainers):

- `test/integration/ansible_vault_test.clj` (needs Docker):
  - ansible-vault encrypts, Clojure decrypts.
  - Clojure encrypts, ansible-vault decrypts.
  - With a fixed salt (`ANSIBLE_VAULT_ENCRYPT_SALT`) both produce byte-identical files.
  - Covers unicode, empty, block-aligned and 1 MiB binary plaintext; UTF-8 and whitespace-padded password files;
    vault-ids; `encrypt_string` output; wrong passwords and tampered data.
- `test/unit/ansible_vault/`: the applicable cases of ansible-core's `test_vault.py`, on fixtures generated by
  ansible-core.
  - Regenerate the fixtures with `clojure -M:dev -m integration.gen-ansible-vault-fixtures` (needs Docker).
  - These tests also run in Babashka (`bb test-bb`).
