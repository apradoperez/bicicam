# Vendored libraries

`maven/` is a local Maven repository so the project builds without access to GitHub Packages.

- `io.hammerhead:karoo-ext:1.1.9` — from https://github.com/hammerheadnav/karoo-ext (Apache-2.0, see `maven/io/hammerhead/karoo-ext/LICENSE`)
  - `karoo-ext-1.1.9.aar` SHA-256: `5180a62451aa16b02a0f3878006d04509f202f66eb7000e9ff6e546aca7ef1d8` (downloaded November 2026)

## Updating karoo-ext

Download the new version's `.aar`, `.pom` and `.module` files from
https://github.com/hammerheadnav/karoo-ext/packages/2175616 into
`maven/io/hammerhead/karoo-ext/<version>/`, then bump the version in `gradle/libs.versions.toml`.
