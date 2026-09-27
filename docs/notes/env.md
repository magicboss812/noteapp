# Environment
Filled by P00-T01 and P00-T03. Keep <= 200 lines.

## Machines
| Machine | OS / shell | JDK | Android SDK path | Last doctor run |
|---|---|---|---|---|
| surface (Surface, Arch Linux, kernel 6.19.8-arch1-3-surface) / bash 5.3.20 | OpenJDK 21.0.12.1 (/usr/lib/jvm/java-21-openjdk, JAVA_HOME unset) | /opt/android-sdk (root-owned) | 2026-09-27: FAIL (licenses, no platform) |

Surface SDK contents (2026-09-27): platform-tools 37.0.1, build-tools 37.0.0, cmdline-tools 23.0, platforms: none.
Latest stable platform per dl.google.com repository2-3.xml (channel stable): `platforms;android-37.2`.
git 2.55.0, jq 1.8.2. `sdkmanager` is deprecated and wraps the new `android sdk` CLI; it needs a writable SDK dir even to list.

## Toolchain versions
(AGP, Gradle, Kotlin, KSP, Compose BOM; filled in P00-T03)

## Dependencies
| Name | Version | License | Source URL | Reason |
|---|---|---|---|---|

## Platform differences
- Windows: Claude Code runs shell commands through Git Bash; scripts use LF line endings (.gitattributes). adb is `adb.exe` under `%ANDROID_HOME%\platform-tools`.
- Arch: standard bash; make scripts executable after cloning (README).
