<#
.SYNOPSIS
    What the tools/dev scripts need to know about this mod, read from gradle.properties.

.DESCRIPTION
    Dot-sourced by the other scripts, never run on its own.

    The mod id and version decide the jar's name, and the id decides the default game folder.
    Reading them here is what keeps every script in tools/dev identical across all mods built
    from minecraft-mod-starter-template: a fix to one copies over to the others as a file, with nothing
    to rename.

    The game folder is a Minecraft Launcher installation of its own, so a mod's test worlds,
    configs and logs never mix with real saves or with another mod's. Create an installation
    for NeoForge 26.2 and set its game directory to

        %APPDATA%\.minecraft\<mod_id>

    or point the MC_GAME_DIR environment variable at a different folder.

    The dev client (mc-devclient.ps1) runs from the repository's run/ folder instead, and joins the
    world named by dev_world in gradle.properties.

    In a multi-project build, mod_project in gradle.properties names the Gradle project that builds
    the mod jar; the jar and the run tasks are then that project's.

    Defines: $RepoRoot, $ModId, $ModVersion, $ModJar, $ModGameDir, $ModDevWorld, $ModRunClientTask.
#>

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

$propertiesPath = Join-Path $RepoRoot "gradle.properties"
if (-not (Test-Path $propertiesPath)) { throw "No gradle.properties at $propertiesPath." }

$ModProperties = @{}
foreach ($line in Get-Content $propertiesPath) {
    if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*?)\s*$') {
        $ModProperties[$Matches[1]] = $Matches[2]
    }
}

$ModId = $ModProperties["mod_id"]
$ModVersion = $ModProperties["mod_version"]
if (-not $ModId -or -not $ModVersion) { throw "gradle.properties has no mod_id or no mod_version." }

# The jar is <mod_jar_name>-<mod_version>-<minecraft_version>.jar; without mod_jar_name, <mod_id>-<mod_version>.jar.
$ModJarFile = if ($ModProperties["mod_jar_name"]) {
    "{0}-{1}-{2}.jar" -f $ModProperties["mod_jar_name"], $ModVersion, $ModProperties["minecraft_version"]
} else {
    "$ModId-$ModVersion.jar"
}

$ModProject = $ModProperties["mod_project"]
if ($ModProject) {
    $ModJar = Join-Path $RepoRoot "$ModProject\build\libs\$ModJarFile"
    $ModRunClientTask = ":${ModProject}:runClient"
} else {
    $ModJar = Join-Path $RepoRoot "build\libs\$ModJarFile"
    $ModRunClientTask = "runClient"
}
$ModGameDir = if ($env:MC_GAME_DIR) { $env:MC_GAME_DIR } else { Join-Path $env:APPDATA ".minecraft\$ModId" }
$ModDevWorld = if ($ModProperties["dev_world"]) { $ModProperties["dev_world"] } else { "Test Arena" }
