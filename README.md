Avec Moi
========

## Introduction

**Avec Moi** is an IntelliJ plugin for *collaborative coding* — the inverse of the usual
agent loop. Instead of the AI writing everything while you watch, the AI **scaffolds and
designs**, **you implement the load-bearing pieces**, and the AI **reviews**. The goal is
to keep you engaged and fluent in your own codebase — "with me," not "for me."

It works by running a small MCP server inside the IDE that an agent (e.g. Claude Code)
connects to. The agent designs a change, writes stubs that compile, and registers
**handoffs** — `(file, line, note)` spots for you to implement. A "Step Done" nudge in the
editor lets you hand control back for review.

> **Note:** Somewhat ironically, this project was 100% vibe coded with
> [Claude Code](https://claude.com/claude-code).

## Features

 * In-IDE MCP server the agent connects to (configurable port; Origin/Host validated; shared-secret auth).
 * **Handoffs** — the agent marks spots for you to implement, navigable in the IDE. Anchored to
   range markers so they follow edits within a session.
 * **Per-project guidance** — record how involved you want to be (goal, self-reported familiarity,
   review depth) so the agent scaffolds vs. hands off accordingly.
 * **Step Done** editor nudge that signals the agent to review the current handoff.
 * Session management (create / switch / rename / complete) scoped per project.

## Installation

Not yet published to the JetBrains Marketplace. Build and install from disk (see
[Manual Installation](#manual-installation)).

## Development Notes

### System Requirements

Java 21, targeting IntelliJ Platform 2026.1 (build 261) and up.

### Manual Installation

Clone the repository and run:

```bash
./gradlew buildPlugin
```

Then in IntelliJ navigate to:

```
Settings > Plugins > ⚙ > Install Plugin from Disk... > build/distributions/avec-moi-<version>.zip
```

### Common Gradle Tasks

```bash
./gradlew test         # Run the test suite
./gradlew buildPlugin  # Build the distributable plugin zip
./gradlew runIde       # Launch a sandbox IDE with the plugin loaded
./gradlew runIde262    # Same, but launch a 2026.2 IDE (forward-compat check)
```

### Commit Format

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).
The main practical effect is that `ci`/`chore`/`docs`/`build`/`style`/`refactor`/`test` commits
are excluded from the auto-generated changelog.

## Continuous Integration & Releases

CI mirrors the structure of the author's other IntelliJ plugins.

### Versioning

Plugin versions are `MAJOR.yyMMdd.BUILD[-suffix]`:
 * `MAJOR` comes from `pluginMajorVersion` in `gradle.properties` (e.g. `261`).
 * `yyMMdd` is the build date; `BUILD` is `BUILD_NUMBER` (CI) or a local counter.
 * On a release branch named `NNN.x` the suffix is dropped; otherwise the git sha (or `BRANCH_NAME`)
   is appended so builds stay distinguishable.

### GitHub Actions — `.github/workflows/main.yml`

Runs on every push to `main`, on pull requests, and on demand. Builds with JDK 21, runs
`./gradlew test buildPlugin`, and publishes unit-test results.

### Jenkins — `ci/`

Two pipelines drive releases:

 * **`release.Jenkinsfile`** — builds (and caches) a pre-warmed build-environment image, then
   builds + tests the plugin. On an `NNN.x` release branch it tags the commit (`vVERSION`) and
   publishes to the JetBrains Marketplace via `publishPluginStandalone`, archiving the artifacts.
 * **`release-branch-automation.Jenkinsfile`** — when an `NNN.x` release branch is built, it
   rewrites `gradle.properties` (`intellijVersion` / `sinceVersion` / `pluginMajorVersion`) to match
   the branch and pushes it back.

`ci/Build-Environment.Dockerfile` defines the build-environment image (Ubuntu + JDK 21 with the
Gradle dependency cache and IntelliJ Platform SDK pre-warmed). `build-cache-init.gradle.kts` and
`repo-cache-init.gradle.kts` are opt-in init scripts (enabled via `-I` on CI) for a remote Gradle
build cache and an internal Nexus mirror, both configured through environment variables.

Contributors
------------
* [Steve Ramage](https://github.com/SJrX)

License
-------
GPLv3 (see [LICENSE](LICENSE)).
