# Installing and updating compoje

compoje runs on [Babashka](https://babashka.org/) (`bb`). It is released as git tags `vX.Y.Z`, and each
[GitHub release](https://github.com/ieugen/compoje/releases) has a ready-made `compoje.jar`.

## Quick install (one line)

With [Babashka](https://babashka.org/) installed, this installs the latest release as a `compoje` command:

```bash
bb -e '(load-string (slurp "https://raw.githubusercontent.com/ieugen/compoje/main/install.clj"))'
```

It downloads `compoje.jar` from the latest GitHub release, checks its SHA-256 against the release's
`compoje.jar.sha256`, keeps it in `~/.local/share/compoje/`, and writes a small `compoje` launcher to
`~/.local/bin` (`exec bb <jar> "$@"`). Then:

```console
$ compoje --version
compoje 0.3.0
```

Options go after the expression:

```bash
# a specific release
bb -e '(load-string (slurp "https://raw.githubusercontent.com/ieugen/compoje/main/install.clj"))' --version v0.3.0
# somewhere else than ~/.local/bin
bb -e '(load-string (slurp "https://raw.githubusercontent.com/ieugen/compoje/main/install.clj"))' --dir /usr/local/bin
# remove it again
bb -e '(load-string (slurp "https://raw.githubusercontent.com/ieugen/compoje/main/install.clj"))' --uninstall
```

| Option | Meaning |
|---|---|
| `--version TAG` | Release to install (default: the latest; or `$COMPOJE_VERSION`). |
| `--dir DIR` | Where the `compoje` command goes (default `~/.local/bin`; or `$COMPOJE_INSTALL_DIR`). Must be on your `PATH`; the installer tells you if it isn't. |
| `--jar-dir DIR` | Where the jar is kept (default `~/.local/share/compoje`). |
| `--from PATH\|URL` | Install this jar instead of downloading a release (checked against `<PATH>.sha256` if it exists). |
| `--force` | Replace a `compoje` command the installer didn't write (e.g. one from bbin). |
| `--uninstall` | Remove the command and the jars. |

**Update:** run the same line again; it replaces the jar and the launcher. **Pin:** add `--version`. The installer
is [install.clj](../install.clj) in the repository; read it before piping it from the internet, or run a copy:
`bb install.clj --help`. To use the installer of a specific release, replace `main` in the URL with the tag
(`.../compoje/v0.3.0/install.clj`).

The command needs only `bb` at run time, no Java and no downloads.

## Other ways to install

| Way | Best for | Needs | Update |
|---|---|---|---|
| [Quick install](#quick-install-one-line) | your workstation, servers | bb only | run the line again |
| [bb task in your project](#per-project-bb-task) | infra repos: every checkout uses the same, pinned compoje | bb, plus Java the first time dependencies are downloaded | change the tag in `bb.edn` |
| [Global command with bbin](#global-command-bbin) | your workstation | bb and [bbin](https://github.com/babashka/bbin) | run `bbin install` again |
| [The jar](#the-jar) | CI jobs and servers | bb only | download the new jar |

All of them run the same code. Check what you have with:

```console
$ bb compoje --version        # or: compoje --version, bb compoje.jar --version
compoje 0.2.0
```

## Per-project bb task

Recommended for repositories that hold your stacks. The compoje version is pinned in the repository, so everyone
who checks it out, and CI, deploys with the same compoje.

Add compoje and a `compoje` task to the project's `bb.edn`:

```clojure
;; bb.edn
{:deps {io.github.ieugen/compoje {:git/tag "v0.2.0" :git/sha "abc1234"}}
 :tasks {:requires ([compoje.cli :as cli])
         compoje {:doc "Run compoje."
                  :task (apply cli/main *command-line-args*)}}}
```

Every release's notes contain this line with the right tag and sha. You can also look up the sha for a tag yourself.
Use the commit the tag points to: for an annotated tag, that's the line ending in `^{}`.

```bash
git ls-remote --tags https://github.com/ieugen/compoje 'v0.2.0*'
```

Then run it with `bb compoje deploy ...`.

The first run downloads compoje and its dependencies into `~/.gitlibs` and `~/.m2`. Babashka resolves them with
tools.deps, which needs **Java** (`java` on PATH or `JAVA_HOME`). Without it, bb stops with
`Couldn't find 'java'. Please set JAVA_HOME.` Later runs use the cache and don't need Java. If Java is not an
option, use [the jar](#the-jar).

**Update:** change `:git/tag` and `:git/sha` to the new release, commit, and run `bb compoje --version`.
Read [CHANGELOG.md](../CHANGELOG.md) first.

**Unreleased version:** use a commit instead of a tag, `{:git/sha "<full 40-character sha>"}`, or a local clone,
`{:local/root "/path/to/compoje"}`.

## Global command (bbin)

[bbin](https://github.com/babashka/bbin) installs Babashka programs as commands on your PATH (by default in
`~/.local/bin`). Two ways:

**From the release jar** (needs only bb):

```bash
bbin install https://github.com/ieugen/compoje/releases/download/v0.2.0/compoje.jar
compoje --version
```

**From git** (needs Java the first time, like the per-project setup):

```bash
bbin install io.github.ieugen/compoje --git/tag v0.2.0
```

Then use `compoje deploy ...` instead of `bb compoje deploy ...`.

**Update:** run the same `bbin install` command with the new version. **Uninstall:** `bbin uninstall compoje`.
List installed programs with `bbin ls`.

A global compoje is handy for trying things out. For stacks in a repository, prefer the per-project task, so the
version is recorded next to the stacks.

## The jar

Every release has `compoje.jar` and `compoje.jar.sha256`. The jar holds compoje and all its dependencies. It needs
only bb, with no Java and no downloads at run time, which makes it a good fit for CI images and servers.

```bash
curl -fLO https://github.com/ieugen/compoje/releases/download/v0.2.0/compoje.jar
curl -fLO https://github.com/ieugen/compoje/releases/download/v0.2.0/compoje.jar.sha256
sha256sum -c compoje.jar.sha256
bb compoje.jar --version
bb compoje.jar deploy --dry-run stacks/web/stack.tpl.yml
```

`https://github.com/ieugen/compoje/releases/latest/download/compoje.jar` always points to the newest release. Pin a
version in CI, so a new release doesn't change your deploys unannounced.

**Update:** download the new jar.

A CI job then needs only bb and the docker CLI:

```bash
curl -fsSL -o compoje.jar https://github.com/ieugen/compoje/releases/download/v0.2.0/compoje.jar
bb compoje.jar deploy --context prod stacks/web/stack.tpl.yml
```

## Which bb version

compoje is tested with Babashka 1.13.x. Older versions may work. Update bb with your package manager or the
[install script](https://github.com/babashka/babashka#installation).
