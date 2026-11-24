# compoje documentation

compoje deploys Docker Swarm stacks from templates. It renders a compose file with your values and secrets, adds
content hashes to config and secret names, and deploys it through the Docker API (or `docker stack deploy`).

## Using compoje

- [quick-start.md](quick-start.md): install compoje and deploy a first stack, step by step
- [install.md](install.md): install and update compoje (bb task, bbin, jar)
- [cli.md](cli.md): commands, flags, exit codes
- [configuration.md](configuration.md): the stack file, the `x-compoje:` keys, precedence, `--set`, the rendering
  context
- [templates.md](templates.md): template syntax, the `file-hash`, `file-path` and `to-yaml` tags, filters,
  `if`/`for`
- [engines.md](engines.md): the two deploy engines (`docker stack deploy` or the Docker API without the docker CLI),
  reaching remote swarms over SSH or TLS, supported compose keys
- [security.md](security.md): where secret values can end up, and how to keep them off disk and out of logs
- [providers.md](providers.md): secret providers (ansible-vault, HashiCorp Vault, plain files) and writing your own
- [ansible-vault.md](ansible-vault.md): secrets in ansible-vault files, in detail
- [whats-new-since-0.1.md](whats-new-since-0.1.md): what changed since compoje 0.1, and how to upgrade a 0.1 stack
- [migrate-from-compoje-edn.md](migrate-from-compoje-edn.md): moving old `compoje.edn` stacks (and 0.3.0 stacks) to the current format
- [known-issues.md](known-issues.md): known limitations and bugs
- [../example-stacks/](../example-stacks/): example stacks (nginx, redis, ansible-vault, file, to-yaml)

## Developing compoje

- [docs.org](docs.org): how compoje works internally
- [releasing.md](releasing.md): how to publish a release
- [dependency-updates.md](dependency-updates.md): dependency update tracker
- [../AGENTS.md](../AGENTS.md): layout, commands and conventions of the code base
- [../CHANGELOG.md](../CHANGELOG.md): changes per release
