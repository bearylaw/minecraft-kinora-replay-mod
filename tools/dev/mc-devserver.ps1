<#
.SYNOPSIS
    Starts (or stops) the local dedicated test server for multiplayer recording tests.

.DESCRIPTION
    Runs `gradlew :kinora-mc:runServer` in the background with run-server/ as its folder: a flat
    creative world, offline mode, port 25566. Kinora is client-only and does not load on it.
    Waits until the server accepts players. Join it with
    tools\dev\kinora-run.ps1 -Script <file> -Server localhost:25566

.PARAMETER Stop
    Stops a running test server.
#>
param([switch] $Stop, [int] $TimeoutSeconds = 300)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "..\..")).Path
$dir = Join-Path $root "run-server"
$log = Join-Path $dir "logs\latest.log"

function Find-Server {
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -match "run-server|net.neoforged.*server" -and $_.CommandLine -match "Server" }
}

if ($Stop) {
    Find-Server | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Write-Output "stopped"
    exit 0
}
New-Item -ItemType Directory -Force $dir | Out-Null
$props = Join-Path $dir "server.properties"
if (-not (Test-Path $props)) {
    @("online-mode=false", "server-port=25566", "level-type=minecraft\:flat", "gamemode=creative", "spawn-protection=0",
      "view-distance=8", "network-compression-threshold=256", "motd=Kinora test server") | Set-Content $props
}
# The dev client's offline player "Dev" is an operator, so scripts can use commands.
$ops = Join-Path $dir "ops.json"
if (-not (Test-Path $ops)) {
    '[{"uuid":"380df991-f603-344c-a090-369bad2a924a","name":"Dev","level":4,"bypassesPlayerLimit":false}]' | Set-Content $ops
}
Remove-Item $log -ErrorAction SilentlyContinue
Start-Process -FilePath (Join-Path $root "gradlew.bat") -ArgumentList ":kinora-mc:runServer" -WorkingDirectory $root -WindowStyle Hidden
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    if ((Test-Path $log) -and (Select-String -Path $log -Pattern 'Done \(' -Quiet)) {
        Write-Output "server ready on localhost:25566"
        exit 0
    }
    Start-Sleep -Seconds 2
}
Write-Output "the server did not start in $TimeoutSeconds s; see $log"
exit 1
