# Atlas SDK consumer check

This is a standalone Gradle project. It is deliberately **not** included in the
root build, so it cannot see the source modules; it can only resolve the published
Maven artifacts.

```powershell
# From the repository root:
.\gradlew.bat publishAtlasToLocalRepo
.\gradlew.bat -p sample/sdk-consumer run
```

The second command uses the root wrapper with `-p`, so it runs this project's own
settings (including a strict `FAIL_ON_PROJECT_REPOS` repository policy) against
`build/atlas-maven`.

What it proves:

- `com.intellitrip.atlas:atlas-engine:0.2.0` resolves with all transitive portable
  modules from a repository that is not the project's own source tree.
- The published POMs are complete enough for a plain Kotlin/JVM host.
- `AtlasEngine` and its public domain types do not require Android or a renderer.

What it does not prove: rendering, Android runtime behavior, or a Flutter platform
channel. Those are host responsibilities documented in `docs/SDK_INTEGRATION.md`.
