<#
.SYNOPSIS
    Starts - or restarts - the Gradle dev client and joins a test world.

.DESCRIPTION
    The other way to see a change, alongside mc-restart.ps1. That one redeploys a built jar into a
    launcher installation; this one runs `gradlew runClient`, which compiles, loads the mod straight
    from the build output and needs no launcher, no account and no copying. It is what an agent
    session should use.

      * stops a dev client that is already running, if any
      * launches `gradlew runClient -Pworld=<World>` in the background, which joins the world
        directly from the title screen's quick-play path
      * waits until the player has actually joined, not merely until a window exists
      * sizes the window so screenshots have room to show detail

    The world has to exist in `run/saves`. Create it once through the game's own menus, and name it
    in gradle.properties as dev_world.

    Gradle's output goes to a log in TEMP; the game's own log is `run/logs/latest.log`.

.PARAMETER World
    The save to join. Defaults to dev_world from gradle.properties.

.PARAMETER Width
    Client area width in pixels; the height follows at 16:9.

.PARAMETER TimeoutSeconds
    How long to wait for the join before giving up.

.PARAMETER GradleArgs
    Extra Gradle arguments, e.g. @("-Preplay=run/kinora/replays/x.kinora").

.PARAMETER NoJoin
    Do not join a world; wait for the title screen instead (for opening a replay).

.PARAMETER Screen
    Where the window goes: "secondary" (default; the first non-primary monitor, if there is one)
    keeps the game off the screen you are working on. The window is moved as soon as it appears,
    without taking focus.
#>
param(
    [string]   $World = "",
    [int]      $Width = 1600,
    [int]      $TimeoutSeconds = 240,
    [string[]] $GradleArgs = @(),
    [switch]   $NoJoin,
    [ValidateSet("secondary", "primary")]
    [string]   $Screen = "secondary"
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here "mc-common.ps1")
if (-not $World) { $World = $ModDevWorld }

Add-Type @"
using System; using System.Runtime.InteropServices;
public class McDevWindow {
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
"@
[void][McDevWindow]::SetProcessDPIAware()

function Get-DevClient {
    Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -like "*Minecraft*" } | Select-Object -First 1
}

$running = Get-DevClient
if ($running) {
    Stop-Process -Id $running.Id -Force
    Wait-Process -Id $running.Id -Timeout 30 -ErrorAction SilentlyContinue
    Write-Output "stopped the running dev client"
}

# The last run's log has to go before this run starts, or its "joined" line is read as this run's.
# A process that has only just exited can still hold the file for a moment, so keep trying.
$log = Join-Path $RepoRoot "run\logs\latest.log"
for ($attempt = 0; (Test-Path $log) -and $attempt -lt 40; $attempt++) {
    Remove-Item $log -Force -ErrorAction SilentlyContinue
    if (Test-Path $log) { Start-Sleep -Milliseconds 250 }
}
if (Test-Path $log) { Write-Error "Could not clear $log; is another client still running?"; exit 1 }
$gradleLog = Join-Path $env:TEMP "mc-devclient-$ModId.log"

$gradleArguments = @($ModRunClientTask, "--console=plain")
if (-not $NoJoin) { $gradleArguments += "-Pworld=`"$World`"" }
$gradleArguments += $GradleArgs
Start-Process -FilePath (Join-Path $RepoRoot "gradlew.bat") `
    -ArgumentList $gradleArguments `
    -WorkingDirectory $RepoRoot -WindowStyle Hidden `
    -RedirectStandardOutput $gradleLog -RedirectStandardError "$gradleLog.err" | Out-Null
Write-Output "launched gradlew $ModRunClientTask$(if (-not $NoJoin) { ", joining '$World'" })"

Add-Type -AssemblyName System.Windows.Forms
$targetArea = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
if ($Screen -eq "secondary") {
    $other = [System.Windows.Forms.Screen]::AllScreens | Where-Object { -not $_.Primary } | Select-Object -First 1
    if ($other) { $targetArea = $other.WorkingArea }
}
$placed = $false
function Place-Window {
    $c = Get-DevClient
    if (-not $c -or $c.MainWindowHandle -eq [IntPtr]::Zero) { return $false }
    $w = [Math]::Min($Width, $targetArea.Width - 80)
    $h = [int]($w * 9 / 16)
    # SWP_NOZORDER | SWP_NOACTIVATE: move and size without raising or focusing the window.
    [void][McDevWindow]::SetWindowPos($c.MainWindowHandle, [IntPtr]::Zero, $targetArea.X + 40, $targetArea.Y + 40, $w + 16, $h + 39, 0x0014)
    return $true
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$joined = $false
while ((Get-Date) -lt $deadline) {
    if (Test-Path $gradleLog) {
        $gradle = Get-Content $gradleLog -Raw -ErrorAction SilentlyContinue
        if ($gradle -match "BUILD FAILED|Compilation failed") {
            Write-Error "The build failed; see $gradleLog"
            exit 1
        }
    }
    if (Test-Path $log) {
        $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
        if ((-not $NoJoin) -and $text -match "joined the game") { $joined = $true; break }
        if ($NoJoin -and $text -match "Kinora .* loaded" -and $text -match "Sound engine started|OpenAL initialized|Created: ") { $joined = $true; break }
        if ($text -match "Crash report|LoaderExceptionModCrash|has crashed") {
            Write-Error "The client crashed; see $log"
            exit 1
        }
    }
    if (-not $placed) { $placed = Place-Window }
    Start-Sleep -Milliseconds 250
}

$client = Get-DevClient
if ($client) {
    $height = [int]($Width * 9 / 16)
    # The outer window is larger than the client area by the frame and title bar.
    [void](Place-Window)
}

if ($joined) {
    # Chunks are still arriving for a moment after the join.
    Start-Sleep -Seconds 4
    Write-Output "joined '$World'"
} else {
    Write-Error "timed out waiting to join '$World'; see $log"
    exit 1
}
