#!/usr/bin/env python3
"""Builds ar_velocity without Gradle: plain `javac --release 21` and `jar`.

    python build.py            compile and package out/ar_velocity-<version>.jar, print its listing
    python build.py --quiet    same, without the listing

NeoForge 1.21.1 runs Mojang names, so the classes are used as compiled: no remapping, no refmap.
Where the JDK and the jars to compile against are is read from build.local.json: build.example.json lists the
entries, BUILDING.md says where each file comes from. Nothing outside this folder is written. Inputs are only
read; the jars that are given by path, and the one nested in another jar, are copied into work/lib first, because
the JDK tools cannot open a path with characters outside the platform code page.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

MOD_ID = "ar_velocity"
VERSION = "0.1.4"

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src" / "main" / "java"
RESOURCES = ROOT / "src" / "main" / "resources"
OUT = ROOT / "out"
CLASSES = OUT / "classes"
JAR = OUT / f"{MOD_ID}-{VERSION}.jar"
LIB_CACHE = ROOT / "work" / "lib"

SETTINGS_FILE = ROOT / "build.local.json"
SETTINGS_KEYS = ("jdk_home", "minecraft_libraries", "version_json", "neoforge_merged_jar", "neoforge_universal_jar",
                 "iris_jar", "accelerated_rendering_jar")
MIXINEXTRAS_NESTED = r"META-INF/jarjar/mixinextras-neoforge-[\w.+-]+\.jar"

# The two compute shaders as released. They keep the line endings of the Accelerated Rendering originals they are
# modified copies of (CRLF, no newline at the end of the file), which a checkout must not change.
SHADERS = {
    "assets/ar_velocity/shaders/compat/transform/iris_entity_vertex_transform_shader.compute":
        "af85fdcaff90442d1e1f1bcfd62ddeb93e5d9c19e8a389ca08744080e75fc75b",
    "assets/ar_velocity/shaders/compat/uploading/iris_entity_mesh_uploading_shader.compute":
        "6af5744156cbdf7610e84b26dcc6f88995aabafdd199b02d4e302038e1e2ed32",
}

_settings = None


def fail(message):
    sys.exit(f"build.py: {message}")


def settings():
    """The entries of build.local.json. A path in it is absolute or relative to this folder."""
    global _settings
    if _settings is None:
        if not SETTINGS_FILE.is_file():
            fail(f"{SETTINGS_FILE.name} is missing: copy build.example.json to that name and enter your paths (see BUILDING.md)")
        try:
            values = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except ValueError as error:
            fail(f"{SETTINGS_FILE.name} is not valid JSON: {error}")
        if not isinstance(values, dict):
            fail(f"{SETTINGS_FILE.name} has to hold one JSON object")
        missing = [key for key in SETTINGS_KEYS if not values.get(key)]
        if missing:
            fail(f"{SETTINGS_FILE.name} has no value for: {', '.join(missing)}")
        _settings = values
    return _settings


def setting(key):
    return ROOT / settings()[key]


def version_manifests():
    """One file, or several when the launcher keeps the libraries of the game in more than one manifest."""
    value = settings()["version_json"]
    return [ROOT / name for name in ([value] if isinstance(value, str) else value)]


def tool(name):
    path = setting("jdk_home") / "bin" / (name + (".exe" if os.name == "nt" else ""))
    if not path.is_file():
        fail(f"{path} not found: jdk_home has to be the home folder of a JDK 21 or newer")
    return str(path)


def run(command, **kwargs):
    result = subprocess.run(command, text=True, capture_output=True, encoding="utf-8", errors="replace", **kwargs)
    return result.returncode, (result.stdout + result.stderr).strip()


def cached_copy(source, name=None):
    """Copies a file into work/lib (once, refreshed when it changes) and returns the copy."""
    if not source.is_file():
        fail(f"missing input: {source}")
    target = LIB_CACHE / (name or source.name)
    stat = source.stat()
    if not target.is_file() or target.stat().st_size != stat.st_size or target.stat().st_mtime < stat.st_mtime:
        LIB_CACHE.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    return target


def nested_jar(outer, pattern):
    """Extracts the one jar with such a name that is stored inside another jar into work/lib."""
    if not outer.is_file():
        fail(f"missing input: {outer}")
    with zipfile.ZipFile(outer) as archive:
        found = [name for name in archive.namelist() if re.fullmatch(pattern, name)]
        if len(found) != 1:
            fail(f"{len(found)} entries of {outer.name} match {pattern}, expected one")
        info = archive.getinfo(found[0])
        target = LIB_CACHE / Path(found[0]).name
        if not target.is_file() or target.stat().st_size != info.file_size:
            LIB_CACHE.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(info))
    return target


def merged_jar():
    return cached_copy(setting("neoforge_merged_jar"))


def iris_jar():
    return cached_copy(setting("iris_jar"))


def ar_jar():
    return cached_copy(setting("accelerated_rendering_jar"))


def mixinextras_jar():
    """MixinExtras as NeoForge ships it, nested in its universal jar."""
    return nested_jar(setting("neoforge_universal_jar"), MIXINEXTRAS_NESTED)


def game_libraries():
    """The library jars of the game, as its version manifest lists them (Windows x64 natives only)."""
    libraries = setting("minecraft_libraries")
    jars = []
    for manifest_file in version_manifests():
        if not manifest_file.is_file():
            fail(f"missing input: {manifest_file}")
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
        for library in manifest["libraries"]:
            parts = library["name"].split(":")
            group, artifact, version = parts[:3]
            classifier = parts[3] if len(parts) > 3 else None
            if classifier is not None and classifier not in ("api", "natives-windows"):
                continue
            if group == "ca.weblite":
                continue
            name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + ".jar"
            jar = libraries / group.replace(".", "/") / artifact / version / name
            if not jar.is_file():
                fail(f"missing library: {jar}")
            if jar not in jars:
                jars.append(jar)
    return jars


def classpath():
    return [merged_jar(), iris_jar(), ar_jar(), mixinextras_jar()] + game_libraries()


def javac(sources, out_dir, jars, lint="-Xlint:all"):
    """javac --release 21 into a fresh out_dir. Stops on errors, returns the number of warnings."""
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    listing = out_dir.with_name(out_dir.name + "-sources.txt")
    listing.write_text("".join(f'"{source.as_posix()}"\n' for source in sources), encoding="utf-8", newline="\n")
    classpath_file = out_dir.with_name(out_dir.name + "-classpath.txt")
    joined = os.pathsep.join(str(jar) for jar in jars).replace("\\", "/")
    classpath_file.write_text(f'-cp "{joined}"\n', encoding="utf-8", newline="\n")
    command = [tool("javac"), "--release", "21", "-encoding", "UTF-8", "-proc:none", "-g", lint,
               "-d", str(out_dir), f"@{classpath_file}", f"@{listing}"]
    code, output = run(command)
    if output:
        print(output)
    if code != 0:
        fail(f"javac failed with exit code {code}")
    return len(re.findall(r"^(?:.*: )?warning: ", output, re.M))


def compile_sources():
    sources = sorted(SRC.rglob("*.java"))
    if not sources:
        fail(f"no sources under {SRC}")
    # -classfile: annotation classes of libraries that are not on the class path are of no interest here.
    warnings = javac(sources, CLASSES, classpath(), "-Xlint:all,-classfile,-processing")
    classes = sorted(CLASSES.rglob("*.class"))
    print(f"javac --release 21: {len(sources)} sources -> {len(classes)} classes, 0 errors, {warnings} warnings")


def check_shaders():
    for name, expected in SHADERS.items():
        shader = RESOURCES / name
        if not shader.is_file():
            fail(f"missing shader: {shader}")
        digest = hashlib.sha256(shader.read_bytes()).hexdigest()
        if digest != expected:
            fail(f"{name} is not the released file (sha256 {digest}); a checkout that converts line endings does "
                 "that, see .gitattributes")


def package():
    if not (RESOURCES / "LICENSE").is_file():
        fail(f"missing licence text: {RESOURCES / 'LICENSE'}")
    manifest = OUT / "MANIFEST.MF"
    manifest.write_text(
        "Manifest-Version: 1.0\n"
        f"Implementation-Title: {MOD_ID}\n"
        f"Implementation-Version: {VERSION}\n",
        encoding="utf-8", newline="\n")
    if JAR.exists():
        JAR.unlink()
    command = [tool("jar"), "--create", "--file", str(JAR), "--manifest", str(manifest),
               "-C", str(CLASSES), ".", "-C", str(RESOURCES), "."]
    code, output = run(command)
    if output:
        print(output)
    if code != 0:
        fail(f"jar failed with exit code {code}")


def list_jar():
    with zipfile.ZipFile(JAR) as jar:
        names = [info for info in jar.infolist() if not info.is_dir()]
        print(f"{JAR} ({JAR.stat().st_size} bytes, {len(names)} files)")
        for info in names:
            print(f"  {info.file_size:7d}  {info.filename}")


def main():
    OUT.mkdir(exist_ok=True)
    code, version = run([tool("javac"), "-version"])
    print(version)
    check_shaders()
    compile_sources()
    package()
    if "--quiet" in sys.argv[1:]:
        print(JAR)
    else:
        list_jar()


if __name__ == "__main__":
    main()
