# Deploy engines

compoje renders your stack, then hands it to one of two deploy engines:

| Engine | How it deploys | Needs | Compose support |
|---|---|---|---|
| `docker-api` (default) | talks to the Docker Engine API directly | only Babashka (plus `ssh` for `ssh://` swarms) | a common subset; other keys are an error |
| `docker-cli` (alias `docker`) | runs `docker stack deploy`, with the stack on stdin | the docker CLI | everything `docker stack deploy` supports |

Both create the same services, networks, secrets and configs. For the compose keys `docker-api` supports, this is
checked by compoje's integration tests: a reference stack deployed with `docker stack deploy` and with `docker-api`
must produce identical results.

## Choosing an engine

Pick one per run or per stack. Later wins:

```yaml
x-compoje:
  docker:
    driver: docker-cli
```

```bash
bb compoje --deploy-driver docker-cli deploy stacks/web/stack.tpl.yml
```

`docker-api` is the default: it needs no docker CLI, for example in a CI image that has only Babashka
(`bb compoje.jar`, see [install.md](install.md#the-jar)). Use `docker-cli` for stacks that need compose keys
`docker-api` doesn't support; `docker-api` names the unsupported key when it fails.

`--dry-run` differs: `docker-api` connects to the swarm and only reads (it logs what it would create or update),
so it needs a reachable swarm manager. `docker-cli` only prints the `docker stack deploy` command line and needs no
daemon.

## Connecting to the swarm

Both engines deploy to the same daemon. `docker-cli` lets the docker CLI find it; `docker-api` finds it the same way
the docker CLI does, without needing the CLI:

1. `--context NAME` (or `x-compoje docker.context`)
2. `DOCKER_HOST`
3. `DOCKER_CONTEXT`
4. the current context in `~/.docker/config.json` (`docker context use ...`)
5. the local socket, `unix:///var/run/docker.sock`

Contexts are read from `~/.docker/contexts` (or `$DOCKER_CONFIG/contexts`), so the contexts you created with
`docker context create` keep working. The context named `default` means `DOCKER_HOST` or the local socket.

The ways to reach a swarm:

| Endpoint | How `docker-api` connects | Needs |
|---|---|---|
| `unix:///var/run/docker.sock` | the socket | running on a manager node, or the socket mounted (CI) |
| `ssh://user@host[:port]` | runs `ssh ... docker system dial-stdio` and speaks the API over it, like the docker CLI | local OpenSSH; the `docker` binary on the remote host; the remote user may use the docker socket |
| `ssh://...` with `ssh-mode: forward` | forwards the remote socket to a socket in a private temp directory | local OpenSSH; `AllowStreamLocalForwarding` (on by default) on the server; no remote docker binary |
| `tcp://host:2376` + TLS | mutual TLS with the context's (or `DOCKER_CERT_PATH`'s) `ca.pem`, `cert.pem`, `key.pem` | dockerd started with `--tlsverify` and client certificates |
| `tcp://host:2375` (no TLS) | refused | `allow-insecure-tcp: true` to force it; anyone who can reach the port is root on that host |

SSH uses your normal OpenSSH setup: `~/.ssh/config` (aliases, ports, `ProxyJump`), the ssh-agent and
`known_hosts`. compoje doesn't ask for passwords; use keys or the agent.

TLS checks the server certificate and its hostname, unless the context has "skip TLS verify" set. Keys may be
PKCS#8, PKCS#1 (RSA) or SEC1 (EC); encrypted keys are not supported.

`x-compoje` settings for `docker-api`:

```yaml
x-compoje:
  docker:
    driver: docker-api
    context: prod-swarm          # a docker context
    ssh-mode: forward            # dial-stdio (default) or forward
    allow-insecure-tcp: false    # plain tcp:// without TLS; avoid
```

## What `docker-api` does

The same steps as `docker stack deploy`:

1. Checks the daemon is a swarm manager.
2. Creates the stack's networks that don't exist (named `<stack>_<key>`, overlay by default). External networks
   must exist.
3. Creates secrets and configs:
   - in-memory ones ([`x-compoje: {from|template}`](configuration.md#secrets-and-configs-from-memory)) are created
     if missing;
   - file-based ones are created when missing. An existing one can only get new labels: Docker refuses changed
     content under the same name, and compoje says how to fix it (`file-hash` in the name, or `x-compoje`).
     A config with the same content and labels is left alone (`Unchanged config ...`); a config whose content
     changed is reported on `--dry-run` already. Docker never returns a secret's content, so for an existing secret
     compoje can only say `Would update labels of secret ...` and the deploy fails if the file changed.
   - External ones must exist.
4. Creates or updates the services (`<stack>_<name>`).
5. Unless `--no-prune`, removes the stack's services that are no longer in the file.

Like `docker stack deploy`, it doesn't wait for the services to converge. Check with `docker service ls` or
`docker stack ps`.

Options:

- `--resolve-image always` (default) pins each image to its digest and records the platforms it supports, like
  docker. `changed` re-resolves only images whose tag (as written in the stack) changed, and otherwise keeps the
  digest deployed before; `never` deploys the tag as written. With `--no-with-registry-auth` the registry is asked
  without credentials, so private images stay unpinned (with a warning).
- `--with-registry-auth` (default on) sends registry credentials to the swarm, so nodes can pull private images.
  Credentials come from `~/.docker/config.json`: inline `auths` entries, or a credential helper (`credsStore`,
  `credHelpers`). Helpers run the separate `docker-credential-<name>` program, not the docker CLI. They are sent with
  the registry query, service creation and service updates.
- `--prune` (default on) removes the stack's services that are no longer in the file; `--no-prune` keeps them.

Both engines take the same options, with the same defaults and precedence, and `deploy` logs the ones in effect.
- `--dry-run` connects and only reads. It lists what it would create, update or remove.

Variables in the stack (`${VAR}`, `${VAR:-default}`, `${VAR-default}`, `${VAR:?message}`, `$VAR`, `$$` for a
literal `$`) are substituted from the environment, as `docker stack deploy` does. Prefer compoje values
(`<{ values.x }>`, `--set`) for anything compoje should know about.

## Compose keys supported by `docker-api`

A key not listed here makes `docker-api` stop with one error listing all such keys. Keys starting with `x-` are
allowed everywhere.

- **Top level:** `version`, `services`, `networks`, `volumes`, `secrets`, `configs`
- **Service:** `image`, `command`, `entrypoint`, `environment`, `env_file`, `labels`, `hostname`, `user`,
  `working_dir`, `init`, `stop_grace_period`, `stop_signal`, `tty`, `stdin_open`, `read_only`, `extra_hosts`, `dns`,
  `dns_search`, `cap_add`, `cap_drop`, `sysctls`, `ulimits`, `ports` (short and long syntax, ranges), `networks`
  (list, or map with `aliases`), `configs`, `secrets` (short and long syntax), `volumes` (named, bind, tmpfs; short
  and long syntax), `healthcheck`, `logging`
- **`deploy`:** `mode` (replicated, global), `replicas`, `endpoint_mode`, `labels`, `resources` (`limits`: `cpus`,
  `memory`, `pids`; `reservations`: `cpus`, `memory`), `restart_policy`, `placement` (`constraints`,
  `preferences`, `max_replicas_per_node`), `update_config`, `rollback_config`
- **Network:** `name`, `driver`, `driver_opts`, `attachable`, `internal`, `labels`, `external`, `ipam` (`driver`,
  `config` with `subnet`, `gateway`, `ip_range`)
- **Volume:** `name`, `driver`, `driver_opts`, `labels`, `external`
- **Secret / config:** `file`, `name`, `external`, `labels`, plus compoje's `x-compoje`

Keys that `docker stack deploy` itself ignores (`build`, `container_name`, `depends_on`, `restart`, `links`,
`network_mode`, `privileged`, ...) only print a warning, as with docker.

Not supported, use `docker-cli`: for example `configs`/`secrets` with `template_driver`, `deploy.resources`
`devices`/`generic_resources`, `credential_spec`, `isolation`, `ports` bound to a host IP (not valid in swarm mode
anyway), and newer compose additions such as `include`, `gpus` or `develop`.

File modes are read like docker reads them: `mode: 0440` is octal (YAML 1.1), even though compoje otherwise
parses YAML 1.2.
