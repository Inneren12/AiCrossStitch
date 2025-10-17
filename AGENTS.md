# AGENTS.md — Operating guide for OpenAI Codex in **AiCrossStitch**

> Source-of-truth repo: **Inneren12/AiCrossStitch** (Android/Kotlin, Jetpack Compose, Material 3).  
> Goal: make Codex predictable, safe, and productive on this codebase.

---

## 0) Golden Rules (read first)

- **No breaking upgrades by default.** Do **not** bump Kotlin, AGP, Gradle Wrapper, Compose, or `compileSdk`/`minSdk` unless explicitly requested.
- **No version catalogs.** Always declare dependencies as explicit coordinates, e.g. `implementation("group:artifact:version")`. Do **not** introduce `libs.*` or `versionCatalog`.
- **Minimal diffs.** Keep PRs focused, small, and reversible. Prefer additive changes over wide file moves/renames.
- **Ask before moving files** or changing public APIs; avoid package renames and mass refactors.
- **Prefer unit tests** over instrumentation tests in cloud runs. Instrumentation/Emulator steps are opt‑in.
- **Keep secrets out of code.** Use environment variables or CI secrets; never hardcode tokens.
- **Do not enable internet broadly** in cloud runs: allowlist only the domains listed below.

---

## 1) Repository shape & assumptions

- Android app with Kotlin + Jetpack Compose (Material 3).  
- Gradle build via `./gradlew`.  
- Static analysis tools may include **ktlint** and **detekt** (add if missing, but as a separate PR).

Key directories (conventional):
```
app/
  src/main/java/...     # Kotlin sources
  src/androidTest/...   # Instrumentation tests (avoid in cloud)
  src/test/...          # Unit tests (preferred)
  build.gradle[.kts]
gradle/                 # Gradle wrapper files
gradlew, gradlew.bat
```

---

## 2) Cloud Environment (Codex)

When running in **cloud**:

- **JDK:** 17 (preferred for AGP 8.5.x).  
- **Android SDK:** Only if building APK is required. Otherwise, run unit tests and static checks without SDK.
- **Network:** off by default. If build is needed, allowlist minimal Maven hosts:

Allowlist (minimal):
- `https://repo.maven.apache.org/maven2`
- `https://maven.google.com`
- `https://dl.google.com`

Optional (only if repo uses them):
- `https://plugins.gradle.org`
- `https://jitpack.io`

**Cache:** enable Gradle cache between runs.  
**Artifacts:** collect `app/build/outputs/**` on successful builds.

---

## 3) Standard tasks & commands

> Codex: prefer these commands; do not invent alternatives without reason.

### Quick sanity
```bash
chmod +x gradlew
./gradlew --version
./gradlew projects
```

### Build (no emulator)
```bash
./gradlew clean :app:assembleDebug
# For a smaller surface in cloud: build only the module that changed if possible.
```

### Unit tests
```bash
./gradlew testDebugUnitTest
# or module-scoped, e.g. :core:testDebugUnitTest if exists
```

### Android Lint (static checks)
```bash
./gradlew :app:lintDebug
```

### ktlint (style)
```bash
# check
./gradlew ktlintCheck
# format (only when explicitly allowed in the task)
./gradlew ktlintFormat
```

### detekt (static analysis)
```bash
./gradlew detekt
```

### Spotless (if present)
```bash
./gradlew spotlessCheck
./gradlew spotlessApply   # only when allowed for auto-format PRs
```

> If ktlint/detekt/spotless are **absent**, propose a separate PR to add them with minimal config (see §6). Do not mix tool adoption and feature fixes in the same PR.

---

## 4) CI recipe (GitHub Actions)

Create `.github/workflows/gradle.yml` (or update minimally):

```yaml
name: Android CI

on:
  pull_request:
    branches: [ main ]
  push:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
          cache: gradle

      - name: Gradle Wrapper Validation
        uses: gradle/wrapper-validation-action@v2

      - name: Build
        run: ./gradlew :app:assembleDebug --no-daemon

      - name: Unit tests
        run: ./gradlew testDebugUnitTest --no-daemon

      - name: Lint
        run: ./gradlew :app:lintDebug --no-daemon

      # Optional static analysis (if configured)
      - name: ktlint
        if: ${{ always() }}
        run: ./gradlew ktlintCheck --no-daemon

      - name: detekt
        if: ${{ always() }}
        run: ./gradlew detekt --no-daemon

      - name: Upload APK
        if: ${{ always() }}
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk
```

**Notes**
- Use **Java 17** unless explicitly requested otherwise.
- Prefer `--no-daemon` in CI for predictable logs.
- Keep jobs independent; do not cache Gradle at the expense of correctness.

---

## 5) PR & commit policy

- **Conventional Commits**, e.g.:
  - `feat(editor): add palette quantization heuristics`
  - `fix(pipeline): prevent OOM on 16MP images`
  - `chore(ci): add ktlint and detekt checks`
- Keep PRs **< 300 lines diff** when possible; split refactors from fixes.
- PR must include:
  - what changed,
  - why (issue/bug/goal),
  - how tested (commands, screenshots, logs),
  - roll-back plan.
- Always attach `./gradlew -v` output and any tool versions changed.

---

## 6) Linters & static analysis (adoption PR)

If tools are missing, create **separate PRs**:

### ktlint (Gradle plugin)
- Add plugin `org.jlleitschuh.gradle.ktlint` to the build (root or per-module).
- Enable tasks `ktlintCheck` and `ktlintFormat`.
- Add `.editorconfig` with Kotlin/Compose rules (basic 2-space indent, max line length 120 by default).
- Do **not** auto-format across the repo without approval; start with `:app` only.

### detekt
- Add `io.gitlab.arturbosch.detekt` plugin with a minimal `detekt.yml` (enable common bug-prone rules, suppress style nitpicks initially).
- Wire into CI as non-blocking at first (`if: ${{ always() }}`), then make blocking later.

> Respect the **No version catalogs** rule (§0). Declare plugin versions explicitly in `plugins {}` and library versions in `dependencies {}`.

---

## 7) Safe build & memory policy (Android images)

- Avoid `BitmapFactory.decode*` without `inSampleSize` on large images. Target preview ~12–16 MP maximum unless otherwise required.
- Prefer `ImageDecoder` on API 28+; fall back to `BitmapFactory` with proper options on lower APIs.
- Guard EXIF handling for orientations {5,7} (transpose/transverse) if image transforms are touched.
- Add unit tests for image utils where feasible (pure JVM; no Android runtime required).

---

## 8) What Codex **should do** vs **should not do**

**Codex SHOULD:**
- Run commands from §3 exactly as written unless task specifies otherwise.
- Ask (via comments) before changing public API or doing structural refactors.
- Produce **unified diffs** in PR descriptions when changes are non-trivial.
- Keep dependency updates isolated in a dedicated PR with full changelog links.

**Codex SHOULD NOT:**
- Introduce `versionCatalog` or replace explicit deps with `libs.*`.
- Bump AGP/Kotlin/Gradle/Compose/reactive versions without explicit approval.
- Change `minSdk`/`targetSdk`/`compileSdk` unless asked.
- Force-enable Android instrumentation tests in cloud by default.

---

## 9) Troubleshooting playbook (quick)

- **DexingNoClasspathTransform / classpath issues**  
  Run `./gradlew --stop && ./gradlew clean build -i`. Verify JDK=17 and dependency graph (`./gradlew dependencies`).

- **OutOfMemory during image ops**  
  Ensure downsampling; avoid allocating multiple full-size intermediates; prefer streaming/tiling when feasible.

- **Conflicting declarations / overload ambiguity**  
  Check duplicate fields/methods after merges; run `ktlint` and `detekt` to catch shadowing and visibility issues.

- **Unresolved reference in multi-module**  
  Verify module dependency order; ensure `api` vs `implementation` is correct and `kotlinOptions` align.

---

## 10) Task templates (for Codex prompt)

**Fix a failing unit test:**
> Run unit tests, pinpoint failure, propose a minimal fix with reasoning, apply diff, and open a PR titled `fix(module): …`. Do not upgrade libraries. Include how reproduced and how verified (commands + logs).

**Adopt ktlint in :app only:**
> Add ktlint plugin in :app, add `.editorconfig`, wire `ktlintCheck` to CI (non-blocking). Provide a separate PR, limited changes. No mass auto-format.

**Add detekt:**
> Add detekt with minimal rules and CI step (non-blocking). Do not fail the pipeline initially; provide sample report.

**Reduce memory use in image loader:**
> Audit decoding path, enforce 16MP cap, unify EXIF rotate (incl. 5 & 7), add unit tests for utils. Keep changes local and documented.

---

## 11) Ownership & approvals

- Default approver: repository maintainer(s).  
- Codex **must** stay in **Read-Only** or **Ask-to-apply** mode unless explicitly elevated for a task.
- Elevate to **Full Access** only for mechanical refactors after approval.

---

## 12) Artifacts & outputs

- APKs: `app/build/outputs/apk/**`
- Test reports: `**/build/reports/tests/**`
- Lint reports: `**/build/reports/lint-results*.html`
- Detekt reports (if enabled): `**/build/reports/detekt/**`

---

### Appendix A — Minimal `.editorconfig` starter (drop in repo root)

```
root = true

[*.{kt,kts}]
indent_style = space
indent_size = 2
max_line_length = 120
insert_final_newline = true
charset = utf-8
end_of_line = lf

ktlint_disabled_rules = no-wildcard-imports
```

---

### Appendix B — Minimal `detekt.yml` starter (place in repo root)

```yaml
build:
  maxIssues: 0
config:
  validation: true
processors:
  active: true
style:
  MagicNumber:
    active: false
  MaxLineLength:
    active: true
    maxLineLength: 120
  WildcardImport:
    active: false
complexity:
  TooManyFunctions:
    active: true
    thresholdInFiles: 20
```

---

**End of AGENTS.md**
