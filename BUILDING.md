# Building AR Velocity

There is no Gradle project. `build.py` calls `javac --release 21` and `jar` directly. NeoForge 1.21.1 runs Mojang names, so the classes are used as compiled, without remapping.

## What Is Needed

- Python 3.8 or newer.
- A JDK 21 or newer. The released jar was built with javac 21.0.11.
- A Minecraft 1.21.1 installation with NeoForge 21.1.x: its `libraries` folder and the version manifest (JSON) of its NeoForge profile. The manifest has to list the libraries of the game; when a launcher keeps the vanilla libraries in a manifest of their own, give both files as a list.
- The NeoForge universal jar of that installation, `libraries/net/neoforged/neoforge/<version>/neoforge-<version>-universal.jar`. MixinExtras is taken out of it.
- The Minecraft and NeoForge classes in one jar with Mojang names. The ModDevGradle plugin of NeoForge writes such a jar to `build/moddev/artifacts/neoforge-<version>-merged.jar` of any mod project that is set up for that NeoForge version.
- The jar of Iris 1.8.x for NeoForge 1.21.1 and the jar of Accelerated Rendering 1.0.14 (`acceleratedrendering-1.0.14-1.21.1-alpha.jar`).

Only the Windows x64 natives are taken from the manifest, and the script has only been run on Windows.

## Steps

1. Copy `build.example.json` to `build.local.json` and enter your paths. A path is absolute or relative to this folder; forward slashes work on Windows. The entry `_about` only explains the others and may stay or go. `build.local.json` is ignored by git.
2. Run `python build.py` (or `python build.py --quiet`).
3. The mod is `out/ar_velocity-0.1.4.jar`.

The script reads its inputs and writes only into `out/` and `work/` of this folder. Keep the repository in a folder whose path has ASCII characters only: javac is handed the list of sources in a file, which it reads in the platform code page.

## Reproducing The Released Jar

`ar_velocity-0.1.4.jar` (74,196 bytes, SHA-256 `091dc165f34c37d63f20edeb8534be37d1abdcfa49eeab44e543e7284bb82ea5`) was built from these sources with javac 21.0.11 against NeoForge 21.1.227, Accelerated Rendering 1.0.14-1.21.1-alpha and a snapshot build of Iris 1.8.13. With the same inputs `build.py` produces the same 41 files byte for byte, the manifest included; the jar file itself differs, because every entry carries the time it was written.

## Shaders

The two compute shaders under `src/main/resources/assets/ar_velocity/shaders` are modified copies of the Iris entity vertex transform and mesh uploading shaders of Accelerated Rendering 1.0.14 (MIT License, see `src/main/resources/LICENSE-AcceleratedRendering.txt`). They keep the line endings of the originals: CRLF, and no newline at the end of the file. `.gitattributes` therefore stores everything under `src/main` byte for byte, and `build.py` stops when the shaders are not the released files.

## Tests

The offline test suite is not part of this repository.
