# compoje quick start

compoje deploys [Docker Swarm](https://docs.docker.com/engine/swarm/) stacks from a template:

- it renders a compose stack file with your values and secrets,
- it adds a content hash to config and secret names, so changing a file creates a new (immutable) Swarm object
  instead of failing the deploy,
- then it runs `docker stack deploy`.

This guide takes you from nothing to a deployed stack in a few minutes.

## Prerequisites

- [Babashka](https://babashka.org/) (`bb`). compoje is tested with a recent version (1.13.x):

  ```bash
  bb --version
  ```

- Docker with Swarm mode. To try compoje locally, make your machine a single-node swarm:

  ```bash
  docker swarm init
  ```

  To deploy to a remote swarm, use a [docker context](https://docs.docker.com/engine/context/working-with-contexts/)
  that points at a manager node (see `--context` below).

## Install

compoje runs as a Babashka task. Create a project directory with a `bb.edn`:

```clojure
;; bb.edn
{:deps {io.github.ieugen/compoje {:git/sha "<commit-sha>"}}
 :tasks {:requires ([compoje.cli :as cli])
         compoje {:doc "Run compoje."
                  :task (apply cli/main *command-line-args*)}}}
```

Replace `<commit-sha>` with a full commit SHA from <https://github.com/ieugen/compoje>, for example the latest one:

```bash
git ls-remote https://github.com/ieugen/compoje HEAD
```

If you have a local clone instead, point at it:

```clojure
{:deps {io.github.ieugen/compoje {:local/root "/path/to/compoje"}}
 ...}
```

Check that it works (the first run downloads dependencies):

```console
$ bb compoje --help
NAME
  compoje  ——  Templates for docker swarm stacks.

SYNOPSIS
  compoje [-v | --verbose] [--deploy-driver DRIVER] [deploy] [<args>...]
...
SUBCOMMANDS
  deploy <stack-file>   Deploy a stack template to a Docker Swarm cluster.
```

`bb compoje deploy --help` lists all deploy options.

## Your first stack

A compoje stack file is a normal compose file plus an `x-compoje:` section with compoje's settings.
Docker ignores `x-` keys, so the rendered file stays a valid compose file.

Create `hello/stack.tpl.yml`:

```yaml
x-compoje:
  docker:
    stack: hello          # the swarm stack name
  values:                 # your own values, used in the template below
    replicas: 1
    port: 8080

services:
  web:
    image: docker.io/nginx:stable
    ports:
      - "<{ values.port }>:80"
    deploy:
      replicas: <{ values.replicas }>
    configs:
      - source: index
        target: /usr/share/nginx/html/index.html

configs:
  index:
    file: html/index.html
    name: index-<% file-hash 'html/index.html' %>
```

and the page it serves, `hello/html/index.html`:

```html
<h1>Hello from compoje!</h1>
```

The template uses two kinds of placeholders:

- `<{ values.port }>` inserts a value. Values come from `x-compoje`, from `--set` on the command line, and from
  secret providers.
- `<% file-hash 'html/index.html' %>` runs a tag. Here it inserts a short hash of the file's content, so the config
  gets a new name whenever the file changes. Paths are relative to the stack file.

compoje uses `<{ }>` and `<% %>` instead of the usual `{{ }}` and `{% %}`, so they don't clash with Swarm's own
service templates (like `hostname: "{{.Node.Hostname}}"`), which you can still use in the stack file.

## Preview the result

`--dry-run` renders the stack and shows the docker command, without deploying anything:

```console
$ bb compoje deploy --dry-run hello/stack.tpl.yml
docker stack deploy --with-registry-auth --resolve-image always --compose-file /.../hello/stack.generated.yml hello
...
services:
  web:
    image: docker.io/nginx:stable
    ports:
      - "8080:80"
    deploy:
      replicas: 1
...
configs:
  index:
    file: html/index.html
    name: index-ff6e66
```

Between the command and the rendered stack it also prints the context, i.e. all the values available to the template.

## Deploy

```console
$ bb compoje deploy hello/stack.tpl.yml
Deploy using: docker stack deploy --with-registry-auth --resolve-image always --compose-file /.../hello/stack.generated.yml hello
Creating network hello_default
Creating config index-ff6e66
Creating service hello_web
```

compoje writes the rendered stack to `hello/stack.generated.yml` and deploys it. Check it:

```console
$ docker stack services hello
NAME        MODE         REPLICAS   IMAGE          PORTS
hello_web   replicated   1/1        nginx:stable   *:8080->80/tcp

$ curl localhost:8080
<h1>Hello from compoje!</h1>
```

## Change something and redeploy

Edit the page, then deploy again. This time scale to two replicas with `--set`:

```bash
echo '<h1>Hello again, compoje!</h1>' > hello/html/index.html
bb compoje deploy --set values.replicas=2 hello/stack.tpl.yml
```

```text
Creating config index-863ce1
Updating service hello_web (id: ...)
```

The new content got a new config name (`index-863ce1`), so Swarm creates a new config and updates the service.
Plain compose files fail here, because Swarm configs and secrets can't be changed in place.

The old config (`index-ff6e66`) stays behind. Remove unused configs from time to time; configs that are still in
use are refused and kept:

```bash
docker config ls -q | xargs docker config rm
```

## Override settings

Settings come from several places. Later ones win:

1. built-in defaults
2. the `x-compoje` section of the stack file
3. `--set path.in.config=value` (repeatable), e.g. `--set values.replicas=2 --set docker.stack=hello-test`
4. deploy flags:

| Flag | Meaning |
|---|---|
| `--stack NAME` | stack name (overrides `x-compoje.docker.stack`) |
| `--context NAME` | docker context to deploy to, e.g. a remote swarm |
| `--prune` | remove services that are no longer in the stack |
| `--resolve-image always\|changed\|never` | image digest resolution (default `always`) |
| `--no-with-registry-auth` | don't send registry credentials to the swarm (sent by default) |
| `--dry-run` | render and print, don't deploy |

Deploy flags go after `deploy`; `-v` (debug logging) goes before it: `bb compoje -v deploy --dry-run hello/stack.tpl.yml`.

## Secrets

compoje can fetch secrets and use them in the template, or write them to files for Docker secrets:

- **ansible-vault**: whole encrypted files and inline `!vault` values, no Ansible needed. See
  [ansible-vault.md](ansible-vault.md). A runnable demo:

  ```bash
  bb compoje deploy --dry-run example-stacks/ansible-vault/stack.tpl.yml   # from a compoje clone
  ```

- **HashiCorp Vault**: KV v2 secrets, provider type `compoje.providers.vault/vault`.

## Clean up

```bash
docker stack rm hello
docker swarm leave --force   # only if you created the swarm just for this guide
```

`docker stack rm` also removes the stack's network and the configs it still uses.

## Troubleshooting

| Message | Fix |
|---|---|
| `[FATAL] No stack name. Set x-compoje docker.stack in the stack file, or pass --stack.` | Add `docker.stack` under `x-compoje`, or pass `--stack`. |
| `[FATAL] Unknown flag: --dryrun` | Typo in a flag. `bb compoje deploy --help` lists them. |
| `[FATAL] Unknown flag: --dry-run` (flag is spelled right) | The flag is before `deploy`. Deploy flags go after `deploy`. |
| `Positional arguments missing: <stack-file>` | Pass the stack file: `bb compoje deploy path/to/stack.tpl.yml`. |
| `[FATAL] .../stack.tpl.yml (No such file or directory)` | Wrong path to the stack file; paths are relative to where you run `bb`. |

Use `-v` (or `-vv`) for debug output, and see [known-issues.md](known-issues.md) for known limitations.
One of them: compoje always renders the file named `stack.tpl.yml` next to the file you pass, so keep that name.

## Next steps

- [example-stacks/](../example-stacks/): more examples (nginx, redis, ansible-vault)
- [ansible-vault.md](ansible-vault.md): secrets from ansible-vault
- [cli.md](cli.md), [configuration.md](configuration.md), [templates.md](templates.md),
  [providers.md](providers.md): the full reference
- [migrate-from-compoje-edn.md](migrate-from-compoje-edn.md): upgrading stacks that still use `compoje.edn`
