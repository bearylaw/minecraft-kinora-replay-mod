<#
.SYNOPSIS
    Installs the test arena datapack into a world and builds the arena.

.DESCRIPTION
    The arena is a datapack function rather than a pile of typed commands, so it can be
    rebuilt at any moment and always comes out identical. That matters more than it sounds:
    a bug reported from a world somebody wrecked while finding it is a bug nobody else can
    reproduce.

    This copies tools/dev/testarena into the world's datapacks folder, reloads, and runs the
    function. Copy plus reload is only needed the first time, or after editing the function;
    after that /function devarena:build on its own is enough, and is what to use to wipe an
    experiment and start again.

    The world has to be open in a running client, because the commands go through the chat
    box - see mc-input.ps1.

.PARAMETER World
    Name of the save to install into. Defaults to the most recently played.

.PARAMETER NoBuild
    Install and reload, but do not run the function.
#>
param(
    [string] $GameDir = "",
    [string] $World = "",
    [switch] $NoBuild
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here "mc-common.ps1")
if ($GameDir -eq "") { $GameDir = $ModGameDir }

$source = Join-Path $here "testarena"
if (-not (Test-Path $source)) { Write-Error "No datapack at $source"; exit 1 }

$saves = Join-Path $GameDir "saves"
if ($World -eq "") {
    $latest = Get-ChildItem $saves -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $latest) { Write-Error "No worlds in $saves"; exit 1 }
    $World = $latest.Name
}

$target = Join-Path $saves "$World\datapacks\dev-arena"
if (-not (Test-Path (Join-Path $saves $World))) { Write-Error "No world named '$World' in $saves"; exit 1 }

# Replaced rather than merged, so a renamed or deleted function never lingers.
if (Test-Path $target) { Remove-Item $target -Recurse -Force }
New-Item -ItemType Directory -Path $target -Force | Out-Null
Copy-Item (Join-Path $source "*") $target -Recurse -Force
Write-Output "installed datapack into world '$World'"

$steps = @("cmd:/reload", "sleep:1500")
if (-not $NoBuild) {
    # The arena is a few dozen fills; give the server a moment before anything else is typed.
    $steps += @("cmd:/function devarena:build", "sleep:4000")
}
& (Join-Path $here "mc-input.ps1") -Do $steps | Out-Null

if ($NoBuild) {
    Write-Output "reloaded; run /function devarena:build when ready"
} else {
    Write-Output "arena built - spawn is the plaza at 40 -59 8"
}
