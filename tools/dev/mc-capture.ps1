<#
.SYNOPSIS
    Captures the running Minecraft window to a PNG.

.DESCRIPTION
    Grabs only Minecraft's client area, never the whole desktop, so nothing else
    on screen ends up in the image. The window has to be visible and unobscured:
    this reads pixels off the screen rather than out of the GPU, which is the one
    method that works for an OpenGL window without injecting into the process.

    Minecraft must run windowed or borderless. Exclusive fullscreen captures black.
#>
param(
    [string] $Out = "",
    [int]    $MaxWidth = 1280,
    [string] $Crop = "",
    [switch] $FullWindow,
    [switch] $Focus
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

Add-Type @"
using System;
using System.Runtime.InteropServices;

public class McWin {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();

    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
"@

# Without this the rectangles come back in virtualised coordinates on a scaled
# display, and the capture is offset and cropped.
[void][McWin]::SetProcessDPIAware()

$proc = Get-Process javaw, java -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like "*Minecraft*" } |
    Select-Object -First 1

if ($null -eq $proc) {
    Write-Error "No Minecraft window found. Is the game running and out of fullscreen?"
    exit 1
}

$hwnd = $proc.MainWindowHandle

if ([McWin]::IsIconic($hwnd)) { [void][McWin]::ShowWindow($hwnd, 9) ; Start-Sleep -Milliseconds 400 }
if ($Focus -and [McWin]::GetForegroundWindow() -ne $hwnd) {
    [void][McWin]::SetForegroundWindow($hwnd)
    Start-Sleep -Milliseconds 250
}

if ($FullWindow) {
    $r = New-Object McWin+RECT
    [void][McWin]::GetWindowRect($hwnd, [ref] $r)
    $x = $r.Left ; $y = $r.Top
    $w = $r.Right - $r.Left ; $h = $r.Bottom - $r.Top
} else {
    $c = New-Object McWin+RECT
    [void][McWin]::GetClientRect($hwnd, [ref] $c)
    $origin = New-Object McWin+POINT
    [void][McWin]::ClientToScreen($hwnd, [ref] $origin)
    $x = $origin.X ; $y = $origin.Y
    $w = $c.Right - $c.Left ; $h = $c.Bottom - $c.Top
}

if ($w -le 0 -or $h -le 0) { Write-Error "Window has no drawable area ($w x $h)." ; exit 1 }

# A region of interest, in client pixels. Reading one corner of the frame costs a
# fraction of reading all of it, which matters over a long debugging session.
if ($Crop -ne "") {
    $c = $Crop -split "[,x ]+" | Where-Object { $_ -ne "" }
    if ($c.Count -ne 4) { Write-Error "-Crop wants 'x,y,width,height'." ; exit 1 }
    $cx = [int] $c[0]; $cy = [int] $c[1]; $cw = [int] $c[2]; $ch = [int] $c[3]
    if ($cx -lt 0 -or $cy -lt 0 -or $cx + $cw -gt $w -or $cy + $ch -gt $h) {
        Write-Error "-Crop lies outside the ${w}x${h} client area." ; exit 1
    }
    $x += $cx ; $y += $cy ; $w = $cw ; $h = $ch
}

if ($Out -eq "") {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
    $Out = Join-Path $env:TEMP "mc-$stamp.png"
}
$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

$shot = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($shot)
$g.CopyFromScreen($x, $y, 0, 0, (New-Object System.Drawing.Size($w, $h)),
                  [System.Drawing.CopyPixelOperation]::SourceCopy)
$g.Dispose()

# Scaled down on the way out: a full-resolution frame costs far more to look at
# than it adds, and portal artefacts stay perfectly visible at this size.
if ($MaxWidth -gt 0 -and $w -gt $MaxWidth) {
    $scale = $MaxWidth / $w
    $sw = [int]($w * $scale) ; $sh = [int]($h * $scale)
    $small = New-Object System.Drawing.Bitmap($sw, $sh)
    $sg = [System.Drawing.Graphics]::FromImage($small)
    $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sg.DrawImage($shot, 0, 0, $sw, $sh)
    $sg.Dispose() ; $shot.Dispose()
    $shot = $small ; $w = $sw ; $h = $sh
}

$shot.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$shot.Dispose()

Write-Output ("{0}|{1}x{2}" -f $Out, $w, $h)
