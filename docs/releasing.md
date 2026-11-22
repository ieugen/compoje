# Releasing compoje

How a maintainer publishes a new version. Users install releases as described in [install.md](install.md).

Releases are git tags `vX.Y.Z` ([semantic versioning](https://semver.org/); while compoje is 0.x, a minor version may
break things). The old `0.1` tag predates this scheme and stays as it is.

The version lives in `resources/compoje/VERSION`, which `compoje --version` prints. Between releases it holds the
next version with `-SNAPSHOT`, e.g. `0.3.0-SNAPSHOT`.

## Steps

1. Make sure `main` is green in CI ([.github/workflows/ci.yml](../.github/workflows/ci.yml)).
2. In [CHANGELOG.md](../CHANGELOG.md):
   - rename `## [Unreleased]` to `## [0.2.0] - YYYY-MM-DD` and add a new, empty `## [Unreleased]` above it
   - update the compare links at the bottom:
     `[Unreleased]: .../compare/v0.2.0...HEAD` and `[0.2.0]: .../compare/0.1...v0.2.0`
3. Set `resources/compoje/VERSION` to `0.2.0`.
4. Check locally:

   ```bash
   bb fmt check && bb lint && bin/kaocha && bb test-bb
   NVD_API_KEY=... bb vuln-scan                       # or check the latest Vulnerabilities workflow run
   bb build-jar && bb target/compoje.jar --version    # compoje 0.2.0
   ```

5. Commit ("Release 0.2.0"), tag and push:

   ```bash
   git tag -a v0.2.0 -m "compoje 0.2.0"
   git push origin main v0.2.0
   ```

6. The tag starts [.github/workflows/release.yml](../.github/workflows/release.yml). It:
   - fails if `VERSION` doesn't match the tag
   - runs all tests and builds `compoje.jar`, then checks `compoje.jar --version` and a dry-run deploy
   - creates the GitHub release with `compoje.jar` and `compoje.jar.sha256`. The notes are the CHANGELOG section
     plus install snippets with the tag and sha, including the one-line installer pinned to the tag.

   The one-line installer (`install.clj` on `main`) downloads these two assets by name, so keep the names.
7. Set `VERSION` to the next snapshot (`0.3.0-SNAPSHOT`) and commit.

## If the release job fails

Fix the problem on `main`, then move the tag:

```bash
git tag -d v0.2.0 && git push origin :refs/tags/v0.2.0    # only if no release was published
git tag -a v0.2.0 -m "compoje 0.2.0" && git push origin v0.2.0
```

Once a release is published, don't move its tag: users pin the sha. Publish `0.2.1` instead.
