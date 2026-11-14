# Atlas SDK consumer check

This is a standalone Gradle project. It is deliberately **not** included in the
root build, so it cannot see the source modules; it can only resolve published
Maven artifacts.

```powershell
# Local staged publication:
.\gradlew.bat publishAtlasToLocalRepo
.\gradlew.bat -p sample/sdk-consumer run

# Published repository (fresh resolution):
.\gradlew.bat -p sample/sdk-consumer run `
  -PatlasMavenUrl=https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven `
  -PatlasVersion=0.2.0 `
  --refresh-dependencies
```

The consumer's settings use a strict `FAIL_ON_PROJECT_REPOS` repository policy.
When `-PatlasMavenUrl` is supplied, the local `build/atlas-maven` default is not
used, so the run proves the published repository is self-contained.

What it proves:

- `com.intellitrip.atlas:atlas-engine:0.2.0` resolves with all transitive
  portable modules from a repository that is not the project's own source tree.
- The published POMs and Gradle module metadata are complete enough for a plain
  Kotlin/JVM host.
- `AtlasEngine` and its public domain types do not require Android or a renderer.

What it does not prove: rendering, Android runtime behavior, or a Flutter platform
channel. Those are host responsibilities documented in `docs/SDK_INTEGRATION.md`
and `docs/ATLAS_MAVEN_DISTRIBUTION.md`.
