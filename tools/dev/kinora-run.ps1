<#
.SYNOPSIS
    Runs a Kinora dev script in the dev client and prints the result.

.DESCRIPTION
    Starts (or restarts) the dev client with the script, waits until the script has finished, and
    prints run/kinora/dev/script.log. Nothing is sent to the desktop: the script drives the game from
    inside (see the DevScript commands in README.md).

    Outputs land in run/kinora/renders (videos and image folders) and run/kinora/dev (screenshots,
    entities.json, exported projects).

.PARAMETER Script
    The script file (any path).

.PARAMETER Shaders
    Also load Sodium + Iris from run/compat and enable the shader pack named in
    run/config/iris.properties. Needs -PnoGlValidation in dev, which this adds (IrisShaders/Iris#3304).

.PARAMETER Join
    Join the test world (for recording tests) instead of stopping at the title screen.

.PARAMETER Server
    Join this multiplayer server (host:port) instead of stopping at the title screen. Start the local
    test server first with tools\dev\mc-devserver.ps1.

.PARAMETER Vulkan
    Use the Vulkan graphics backend for this launch (the default and the video setting stay as they are).
    NeoForge's early loading window is OpenGL-only and stops Vulkan from starting, so it is turned off
    in run/config/fml.toml for the run and turned back on afterwards.

.PARAMETER TimeoutSeconds
    How long to wait for the script to finish.

.EXAMPLE
    tools\dev\kinora-run.ps1 -Script tools\dev\scripts\film.txt
#>
param(
    [Parameter(Mandatory = $true)] [string] $Script,
    [switch] $Shaders,
    [switch] $Join,
    [string] $Server = "",
    [switch] $Vulkan,
    [int] $TimeoutSeconds = 900
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $here "..\..")).Path
$scriptPath = (Resolve-Path $Script).Path -replace '\\', '/'
$dev = Join-Path $root "run\kinora\dev"
$mods = Join-Path $root "run\mods"
$irisConfig = Join-Path $root "run\config\iris.properties"

# A running client holds the mod jars open; stop it before changing them.
Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*Minecraft*" } | Stop-Process -Force
Start-Sleep -Seconds 2
New-Item -ItemType Directory -Force $mods | Out-Null
Get-ChildItem $mods -Filter "*.jar" | Where-Object { $_.Name -match "^(sodium|iris)-" } | Remove-Item -Force
$gradleArgs = @("-PkinoraScript=$scriptPath")
if ($Shaders) {
    Copy-Item (Join-Path $root "run\compat\*.jar") $mods
    if (Test-Path $irisConfig) {
        (Get-Content $irisConfig) -replace '^enableShaders=.*', 'enableShaders=true' | Set-Content $irisConfig
    }
    $gradleArgs += "-PnoGlValidation"
}
if ($Server) {
    $gradleArgs += "-Pserver=$Server"
}
$fmlConfig = Join-Path $root "run\config\fml.toml"
if ($Vulkan) {
    $gradleArgs += "-PgraphicsBackend=vulkan"
    if (Test-Path $fmlConfig) {
        (Get-Content $fmlConfig) -replace '^earlyWindowControl = true', 'earlyWindowControl = false' | Set-Content $fmlConfig
    }
}
Remove-Item (Join-Path $dev "script.done"), (Join-Path $dev "script.log") -ErrorAction SilentlyContinue

if ($Join -and -not $Server) {
    & (Join-Path $here "mc-devclient.ps1") -TimeoutSeconds 400 -GradleArgs $gradleArgs | Out-Null
} else {
    & (Join-Path $here "mc-devclient.ps1") -NoJoin -TimeoutSeconds 400 -GradleArgs $gradleArgs | Out-Null
}

$done = Join-Path $dev "script.done"
$log = Join-Path $root "run\logs\latest.log"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while (-not (Test-Path $done) -and (Get-Date) -lt $deadline) {
    if ((Test-Path $log) -and (Select-String -Path $log -Pattern "Reported exception thrown|has crashed" -Quiet)) {
        Write-Output "The game crashed; see run/logs/latest.log"
        exit 2
    }
    Start-Sleep -Seconds 1
}
if (-not (Test-Path $done)) {
    Write-Output "Timed out after $TimeoutSeconds s; the script log so far:"
}
if ($Vulkan -and (Test-Path $fmlConfig)) {
    (Get-Content $fmlConfig) -replace '^earlyWindowControl = false', 'earlyWindowControl = true' | Set-Content $fmlConfig
}
Get-Content (Join-Path $dev "script.log") -ErrorAction SilentlyContinue
Select-String -Path $log -Pattern "\[Kinora/\]: (Rendered|Kinora render|Kinora rendered)" -ErrorAction SilentlyContinue | ForEach-Object { $_.Line }
if ((Test-Path $done) -and (Get-Content $done) -match "^PASS") { exit 0 } else { exit 1 }
