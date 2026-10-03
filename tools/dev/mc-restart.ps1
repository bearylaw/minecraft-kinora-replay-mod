<#
.SYNOPSIS
    Redeploys the mod jar and restarts Minecraft where it was.

.DESCRIPTION
    A mod jar can only be swapped while the game is down, so testing a code change
    means a restart every time. Done by hand that costs a saved world, the window
    position, and a walk back to the test area. This does all three automatically:

      * flushes the world to disk before stopping, so nothing is lost
      * removes older jars of this mod, so a version bump does not load two copies
      * relaunches with the exact command line the launcher used, so the session
        token, mod path and JVM flags all stay as they were
      * rejoins the most recently played singleplayer world via --quickPlaySingleplayer
      * puts the window back at the size and position it had

    The jar and the game folder come from gradle.properties (see mc-common.ps1). The
    first run needs the game already running, started from the launcher, because that
    is where the command line is captured from. It contains a session token, so it is
    kept in TEMP and never written into the repository. It survives a reboot.
#>
param(
    [string] $Jar = "",
    [string] $GameDir = "",
    [string] $World = "",
    [int]    $TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here "mc-common.ps1")

# Found from gradle.properties rather than written in. The portal gun's copy of this script had
# an absolute jar path pointing at the folder the repository had before it was renamed: the jar
# was then never found, the copy was skipped, and a restart relaunched the build before yours.
if ($Jar -eq "") { $Jar = $ModJar }
if ($GameDir -eq "") { $GameDir = $ModGameDir }

Add-Type @"
using System; using System.Runtime.InteropServices;
public class McWindow {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
[void][McWindow]::SetProcessDPIAware()

function Get-McProcess {
    Get-Process javaw, java -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -like "*Minecraft*" } | Select-Object -First 1
}

# One state file per mod, since each mod runs from its own launcher installation.
$state = Join-Path $env:TEMP "mc-restart-state-$ModId.xml"
$proc = Get-McProcess

if ($proc) {
    $rect = New-Object McWindow+RECT
    [void][McWindow]::GetWindowRect($proc.MainWindowHandle, [ref] $rect)
    $wmi = Get-WmiObject Win32_Process -Filter ("ProcessId = " + $proc.Id)
    @{
        CommandLine = $wmi.CommandLine
        X = $rect.Left; Y = $rect.Top
        W = $rect.Right - $rect.Left; H = $rect.Bottom - $rect.Top
    } | Export-Clixml $state
    Write-Output ("captured window {0}x{1} at {2},{3}" -f ($rect.Right-$rect.Left), ($rect.Bottom-$rect.Top), $rect.Left, $rect.Top)

    Write-Output "flushing world to disk"
    & (Join-Path $here "mc-input.ps1") -Do "cmd:/save-all flush" | Out-Null
    Start-Sleep -Seconds 3

    Stop-Process -Id $proc.Id -Force
    while (Get-McProcess) { Start-Sleep -Milliseconds 500 }
    Write-Output "stopped"
}

if (-not (Test-Path $state)) {
    Write-Error "No saved launch command line for '$ModId', and Minecraft is not running. Start it once from the launcher."
    exit 1
}
$saved = Import-Clixml $state

if (Test-Path $Jar) {
    $mods = Join-Path $GameDir "mods"
    if (-not (Test-Path $mods)) { New-Item -ItemType Directory -Path $mods -Force | Out-Null }
    $leaf = Split-Path -Leaf $Jar
    # A version bump changes the jar's name, and two jars with one mod id refuse to load together.
    # "$ModId-*" also catches jars from before the kinora-replay name; the sample mod is another mod.
    Get-ChildItem $mods -Filter "$ModId-*.jar" | Where-Object { $_.Name -ne $leaf -and $_.Name -notmatch '-sample-' } | ForEach-Object {
        Remove-Item $_.FullName -Force
        Write-Output ("removed older {0}" -f $_.Name)
    }
    Copy-Item $Jar (Join-Path $mods $leaf) -Force
    Write-Output ("deployed {0}" -f $leaf)
} else {
    # Loudly. The whole point of a restart is to pick up a change, so carrying on with whatever
    # jar happens to be installed is the one outcome that wastes an evening.
    Write-Warning ("no jar at {0} - restarting with whatever is already installed. Run a build first." -f $Jar)
}

if ($World -eq "") {
    $World = (Get-ChildItem (Join-Path $GameDir "saves") -Directory |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name
}

# Strip any quickPlay left over from an earlier restart before adding our own,
# or the arguments accumulate and the launcher rejects the duplicate.
$cmd = ($saved.CommandLine -replace '\s--quickPlaySingleplayer\s+("[^"]*"|\S+)', '').Trim()

# The executable may or may not be quoted -- the launcher reports it bare, but a
# relaunch started from here comes back quoted -- so handle both rather than
# slicing at ".exe" and keeping a stray quote.
if ($cmd.StartsWith('"')) {
    $end = $cmd.IndexOf('"', 1)
    $exe = $cmd.Substring(1, $end - 1)
    $rest = $cmd.Substring($end + 1)
} else {
    $end = $cmd.IndexOf(".exe") + 4
    $exe = $cmd.Substring(0, $end)
    $rest = $cmd.Substring($end)
}
$arguments = $rest.Trim() + ' --quickPlaySingleplayer "' + $World + '"'

$log = Join-Path $GameDir "logs\latest.log"
if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue }

# Minecraft mirrors its whole log to the console, and an inherited console would
# dump all of it into this script's output. It already writes latest.log, so send
# the stream to a file nobody reads.
$started = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $GameDir `
    -RedirectStandardOutput (Join-Path $env:TEMP "mc-stdout-$ModId.log") `
    -RedirectStandardError (Join-Path $env:TEMP "mc-stderr-$ModId.log") `
    -PassThru
Write-Output ("launched PID {0}, joining '{1}'" -f $started.Id, $World)

# The window appears well before the world is playable, so wait for the log line
# that only follows a finished join rather than for the window itself.
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$ready = $false
while ((Get-Date) -lt $deadline) {
    if (Test-Path $log) {
        $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
        if ($text -match "LoaderExceptionModCrash|Failed to start the minecraft server") {
            Write-Error "Minecraft failed to start; see $log" ; exit 1
        }
        if ($text -match "Started serving|Time elapsed|Loaded \d+ advancements") { $ready = $true ; break }
    }
    Start-Sleep -Milliseconds 1000
}

$proc = Get-McProcess
if ($proc) {
    # SWP_NOZORDER | SWP_NOACTIVATE, so restoring geometry does not steal focus.
    [void][McWindow]::SetWindowPos($proc.MainWindowHandle, [IntPtr]::Zero,
        $saved.X, $saved.Y, $saved.W, $saved.H, 0x0004 -bor 0x0010)
    Write-Output ("restored window {0}x{1} at {2},{3}" -f $saved.W, $saved.H, $saved.X, $saved.Y)
}

if ($ready) { Write-Output "world loaded" } else { Write-Output "timed out waiting for world load" }
