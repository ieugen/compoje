# Security: where secrets go

compoje handles decrypted secrets: values from ansible-vault, HashiCorp Vault or files. This page lists every place
a secret value can end up, so you can keep it out of disks, logs and git.

## By default, nothing secret is written to disk

| What | Where it goes |
|---|---|
| The rendered stack | Never written. Passed to `docker stack deploy` on stdin (`docker-cli`), or kept in memory (`docker-api`). |
| Docker secrets/configs with `x-compoje: {from: ...}` or `{template: ...}` | Created from memory: content on the docker CLI's stdin, or in the API request. Never written to a file or put on a command line. See [configuration.md](configuration.md#secrets-and-configs-from-memory). |
| Provider results (`<{ app.password }>`) | In memory, in the rendered stack. |

You write something to disk only when you ask for it:

- `compoje render -o FILE` writes the rendered stack, including any secret value the template uses. Delete the file
  when you are done; it is never committed by compoje, but `.gitignore` it anyway (`*.generated.yml`).
- Legacy provider `local-path` writes a secret in plain text, on every run including `--dry-run`. Replace it with
  `x-compoje: {from: ...}`; `compoje migrate <dir>` converts it for you
  ([migrate-from-compoje-edn.md](migrate-from-compoje-edn.md#secrets-written-to-files)) and lists the old files to
  delete.

## What compoje prints

- `deploy --dry-run` prints the rendered stack (stdout) and the context (stderr). In the context, provider
  results show as `<redacted>`; `--show-secrets` shows them. The rendered stack contains whatever the template uses.
- `render` prints the rendered stack.
- `-v` (debug) logs file paths, provider and object names, never secret values. `-vv` (trace) logs the whole
  context, **including decrypted secrets**: use it only for local debugging, never in CI logs.
- In-memory secrets are logged by name and size only ("Would create secret ... (12 bytes)").

## In the swarm

- Docker **secrets** are stored encrypted in the swarm and are only readable inside the containers they are given
  to. Put secret values in secrets.
- Docker **configs** are **not** encrypted, and anyone with access to the Docker API can read them
  (`docker config inspect`). A config built with `x-compoje: {template: ...}` must not contain secret values; use a
  secret for those.
- Names generated as `<stack>_<key>-<hash>` include the first 10 hex digits (40 bits) of the content's SHA-256.
  For a short, guessable value (a PIN, a 6-letter password) that is enough to brute-force it from `docker secret ls`.
  Give such secrets an explicit `name` (e.g. `db_password-v3`) and change it yourself when the value changes.
- Environment variables (`environment:` with `<{ app.password }>`) are visible in `docker service inspect` and to
  anyone who can inspect the containers. Prefer Docker secrets (files under `/run/secrets`) for real secrets.
- A secret value rendered into the stack without `|yaml-str` can be silently changed: docker substitutes `$VAR` in
  the stack, and YAML special characters alter or break it. See
  [templates.md, Escaping values](templates.md#escaping-values).

## Reaching the swarm

- Use `ssh://` contexts or mutual TLS. The `docker-api` engine refuses plain `tcp://` without TLS unless you set
  `allow-insecure-tcp`: an open Docker port is root on that host for anyone who can reach it.
- With SSH, compoje runs your OpenSSH client with your configuration, agent and `known_hosts`. It doesn't
  disable host key checking.
- With `ssh-mode: forward`, the forwarded socket lives in a temporary directory only you can access, removed after
  the deploy.
- TLS verifies the server certificate and hostname unless the context says to skip verification.

## Registry credentials

With `--with-registry-auth` (the default), registry credentials are read from `~/.docker/config.json` or a credential
helper and sent to the Docker daemon, which passes them to the nodes so they can pull private images. This is what
`docker stack deploy --with-registry-auth` does too. compoje never logs or writes them. Use
`--no-with-registry-auth` for public images.

## Checklist

- [ ] No `local-path` in your stacks (run `compoje migrate`), and no old `generated/` files lying around.
- [ ] Secret values go into Docker secrets (`x-compoje: {from: ...}`), not configs or environment variables.
- [ ] Short secrets have an explicit `name`.
- [ ] CI runs without `-vv` and without `--show-secrets`.
- [ ] `*.generated.yml` and `generated/` are in `.gitignore`; vault passwords are never committed.
- [ ] Remote swarms are reached over `ssh://` or mutual TLS.
