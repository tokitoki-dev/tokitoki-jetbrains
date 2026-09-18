# Releasing

## Branches and CI

Daily work goes to `dev`; a plain push there triggers nothing. A pull request
into `main` runs the unit tests, builds a release-configured plugin with the
pinned CLI, checks that all six CLI binaries are bundled, and runs the plugin
verifier against the recommended IDE range. `main` is the release line.

## Cutting a release

1. Bump `pluginVersion` in `gradle.properties`.
2. Add a `## x.y.z` section to `CHANGELOG.md`. The build turns it into the
   Marketplace change notes and the GitHub release notes, and fails when the
   newest section does not match the version.
3. If the bundled CLI changes, update the tag and all six digests in
   `scripts/cli-release-pins.sh` in the same pull request. Never use GitHub's
   `latest` URL: pinning both version and bytes makes a rebuild of an
   existing plugin tag deterministic.
4. Commit to `dev`, open a pull request into `main`, merge once CI passes.
5. Tag `main` and push the tag:

```sh
git switch main
git pull --ff-only
git tag vX.Y.Z
git push origin vX.Y.Z
```

The `Release` workflow rejects a tag that is not on `main` or does not match
`pluginVersion`, prints the build stamps and refuses to continue unless they
are the production ones, builds and verifies the plugin, publishes the ZIP
as a GitHub release, and uploads it to the JetBrains Marketplace.

## Secrets

Settings → Secrets and variables → Actions:

| Secret | What |
| --- | --- |
| `JETBRAINS_MARKETPLACE_TOKEN` | A Marketplace permanent token with upload rights for the plugin |
| `JETBRAINS_CERTIFICATE_CHAIN` | Plugin signing certificate chain (PEM) |
| `JETBRAINS_PRIVATE_KEY` | Plugin signing private key (PEM) |
| `JETBRAINS_PRIVATE_KEY_PASSWORD` | Its password |

Signing is optional for the Marketplace but recommended; without the three
signing secrets `publishPlugin` uploads an unsigned ZIP.

## First publication

The first version of a plugin cannot be uploaded by the API. Upload the ZIP
once by hand at https://plugins.jetbrains.com/plugin/add, wait for the
approval, then the workflow handles every version after it.

## Manual publication

```sh
./gradlew buildPlugin -PtokitokiRelease=true
JETBRAINS_MARKETPLACE_TOKEN=... ./gradlew publishPlugin -PtokitokiRelease=true
```

## Rollback

A published Marketplace version cannot be recalled, only hidden from the
plugin's page. Prefer rolling forward with a new patch version.
