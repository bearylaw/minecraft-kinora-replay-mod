<#
.SYNOPSIS
    Drives the running Minecraft window with synthetic keyboard and mouse input.

.DESCRIPTION
    Uses SendInput, which produces events at the same level the driver does, so
    GLFW accepts them -- including the raw mouse motion Minecraft reads while the
    cursor is grabbed. Higher-level tricks such as SendKeys or posted window
    messages are ignored by the game.

    Because these are real input events they go to whichever window has focus, so
    the script focuses Minecraft first. Keep your hands off the keyboard while a
    sequence runs.

.PARAMETER Do
    A sequence of steps, each "verb:args":

      key:w:down     key:w:up     key:r      key:escape    key:f3
      type:hello world
      move:200:-40                 relative mouse motion (look around)
      click:left     click:right   click:left:down   click:right:up
      wheel:1        wheel:-1
      sleep:250                    milliseconds
      shot:C:\path\frame.png       capture the client area, mid-sequence
      burst:12:60:C:\path\sheet.png
                                   capture 12 frames 60 ms apart into one contact sheet,
                                   numbered left to right, top to bottom
      burst:12:60:C:\path\sheet.png:800,300,480,420
                                   the same, of one region only (client pixels)

    A capture step does not wait for the frames to be drawn, so the input before it is seen as
    quickly as the game shows it. That is the point: a cast, a swing, a flash, caught as it happens.

.EXAMPLE
    mc-input.ps1 -Do "click:left","sleep:400","move:180:0","click:right"
#>
param(
    [Parameter(Mandatory = $true)] [string[]] $Do,
    [switch] $NoFocus
)

$ErrorActionPreference = "Stop"

Add-Type @"
using System;
using System.Runtime.InteropServices;

public class McInput {
    [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT {
        public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo;
    }
    [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT {
        public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo;
    }
    [StructLayout(LayoutKind.Explicit)] public struct INPUT {
        [FieldOffset(0)] public uint type;
        [FieldOffset(8)] public MOUSEINPUT mi;
        [FieldOffset(8)] public KEYBDINPUT ki;
    }

    [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] p, int size);
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();

    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint code, uint mapType);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr pid);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint from, uint to, bool attach);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();

    const uint MOUSE = 0, KEYBOARD = 1;
    const uint KEYEVENTF_KEYUP = 0x0002, KEYEVENTF_UNICODE = 0x0004;

    public static void Key(ushort vk, bool down) {
        INPUT[] inputs = new INPUT[1];
        inputs[0].type = KEYBOARD;
        inputs[0].ki.wVk = vk;
        // Games read the scan code out of the message, so fill it in rather than
        // trusting the system to synthesise one.
        inputs[0].ki.wScan = (ushort) MapVirtualKey(vk, 0);
        inputs[0].ki.dwFlags = down ? 0u : KEYEVENTF_KEYUP;
        SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
    }

    public static void Unicode(char c, bool down) {
        INPUT[] inputs = new INPUT[1];
        inputs[0].type = KEYBOARD;
        inputs[0].ki.wScan = c;
        inputs[0].ki.dwFlags = KEYEVENTF_UNICODE | (down ? 0u : KEYEVENTF_KEYUP);
        SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
    }

    public static void Mouse(uint flags, int dx, int dy, uint data) {
        INPUT[] inputs = new INPUT[1];
        inputs[0].type = MOUSE;
        inputs[0].mi.dx = dx; inputs[0].mi.dy = dy;
        inputs[0].mi.mouseData = data; inputs[0].mi.dwFlags = flags;
        SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
    }

    /// Puts the pointer at an absolute screen position, which is what menus need --
    /// relative motion only steers the camera, and a GUI cursor has no fixed origin
    /// to steer from. Coordinates are normalised across the whole virtual desktop.
    public static void MoveTo(int screenX, int screenY) {
        int originX = GetSystemMetrics(76), originY = GetSystemMetrics(77);
        int spanX = GetSystemMetrics(78), spanY = GetSystemMetrics(79);
        INPUT[] inputs = new INPUT[1];
        inputs[0].type = MOUSE;
        inputs[0].mi.dx = (int) (((double) (screenX - originX)) * 65535 / spanX);
        inputs[0].mi.dy = (int) (((double) (screenY - originY)) * 65535 / spanY);
        inputs[0].mi.dwFlags = 0x0001 | 0x8000 | 0x4000; // MOVE | ABSOLUTE | VIRTUALDESK
        SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
    }

    /// Translates a point inside Minecraft's client area to screen coordinates.
    public static POINT ClientPoint(IntPtr h, int x, int y) {
        POINT p = new POINT(); p.X = x; p.Y = y;
        ClientToScreen(h, ref p);
        return p;
    }

    // SetForegroundWindow refuses callers that are not already foreground, so
    // borrow the current foreground thread's input state for the moment it takes.
    public static bool Focus(IntPtr target) {
        if (IsIconic(target)) { ShowWindow(target, 9); System.Threading.Thread.Sleep(300); }
        IntPtr current = GetForegroundWindow();
        if (current == target) { return true; }
        uint us = GetCurrentThreadId();
        uint them = GetWindowThreadProcessId(current, IntPtr.Zero);
        AttachThreadInput(us, them, true);
        bool ok = SetForegroundWindow(target);
        AttachThreadInput(us, them, false);
        if (!ok || GetForegroundWindow() != target) {
            // Windows only lets the process that received the last input event take the
            // foreground. A synthetic Alt tap makes this process that one.
            keybd_event(0x12, 0, 0, UIntPtr.Zero);
            keybd_event(0x12, 0, 2, UIntPtr.Zero);
            ok = SetForegroundWindow(target);
            // Let the Alt release settle, or a Tab sent next becomes Alt+Tab.
            System.Threading.Thread.Sleep(250);
            ok = GetForegroundWindow() == target;
        }
        return ok;
    }
}
"@

$VirtualKeys = @{
    "escape"=0x1B; "esc"=0x1B; "enter"=0x0D; "return"=0x0D; "space"=0x20; "tab"=0x09;
    "shift"=0x10; "ctrl"=0x11; "control"=0x11; "alt"=0x12; "backspace"=0x08;
    "left"=0x25; "up"=0x26; "right"=0x27; "down"=0x28;
    "f1"=0x70; "f2"=0x71; "f3"=0x72; "f4"=0x73; "f5"=0x74; "f6"=0x75;
    "f7"=0x76; "f8"=0x77; "f9"=0x78; "f10"=0x79; "f11"=0x7A; "f12"=0x7B;
    "home"=0x24; "end"=0x23; "delete"=0x2E; "del"=0x2E; "pageup"=0x21; "pagedown"=0x22; "insert"=0x2D;
    "comma"=0xBC; "period"=0xBE; "lbracket"=0xDB; "rbracket"=0xDD
}

function Resolve-Vk([string] $name) {
    $n = $name.ToLowerInvariant()
    if ($VirtualKeys.ContainsKey($n)) { return [uint16] $VirtualKeys[$n] }
    if ($n.Length -eq 1) {
        $code = [int][char] $n[0]
        if ($code -ge 97 -and $code -le 122) { return [uint16] (0x41 + $code - 97) }
        if ($code -ge 48 -and $code -le 57)  { return [uint16] (0x30 + $code - 48) }
    }
    throw "Unknown key '$name'"
}

[void][McInput]::SetProcessDPIAware()
Add-Type -AssemblyName System.Drawing

# The client area, or a region of it, in screen pixels.
function Get-CaptureRect([string] $region) {
    $c = New-Object McInput+RECT
    [void][McInput]::GetClientRect($hwnd, [ref] $c)
    $o = [McInput]::ClientPoint($hwnd, 0, 0)
    $r = @{ X = $o.X; Y = $o.Y; W = $c.Right - $c.Left; H = $c.Bottom - $c.Top }
    if ($region) {
        $v = $region -split "[,x ]+" | Where-Object { $_ -ne "" } | ForEach-Object { [int] $_ }
        if ($v.Count -ne 4) { throw "A region is 'x,y,width,height', not '$region'" }
        $r = @{ X = $o.X + $v[0]; Y = $o.Y + $v[1]; W = $v[2]; H = $v[3] }
    }
    return $r
}

function Get-Frame($r) {
    $bmp = New-Object System.Drawing.Bitmap($r.W, $r.H)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.X, $r.Y, 0, 0, (New-Object System.Drawing.Size($r.W, $r.H)))
    $g.Dispose()
    return $bmp
}

function Save-Scaled($bmp, [string] $path, [int] $maxWidth) {
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    if ($bmp.Width -gt $maxWidth) {
        $h = [int]($bmp.Height * $maxWidth / $bmp.Width)
        $small = New-Object System.Drawing.Bitmap($maxWidth, $h)
        $g = [System.Drawing.Graphics]::FromImage($small)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.DrawImage($bmp, 0, 0, $maxWidth, $h)
        $g.Dispose()
        $small.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $small.Dispose()
    } else {
        $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
}

$proc = Get-Process javaw, java -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like "*Minecraft*" } | Select-Object -First 1
if ($null -eq $proc) { Write-Error "No Minecraft window found." ; exit 1 }
$hwnd = $proc.MainWindowHandle

if (-not $NoFocus) {
    if (-not [McInput]::Focus($hwnd)) {
        # Real input goes to whatever window is in front: never type into someone else's app.
        Write-Error "Could not bring Minecraft to the front; no input was sent."
        exit 2
    }
    Start-Sleep -Milliseconds 300
}

$MOVE=0x0001; $LDOWN=0x0002; $LUP=0x0004; $RDOWN=0x0008; $RUP=0x0010; $WHEEL=0x0800

foreach ($step in $Do) {
    # Someone may have switched windows mid-sequence: stop rather than type into their app.
    if (-not $NoFocus -and -not ($step -like "sleep:*" -or $step -like "shot:*" -or $step -like "burst:*") -and [McInput]::GetForegroundWindow() -ne $hwnd) {
        Write-Error "Minecraft is no longer in front; stopped before '$step'."
        exit 2
    }
    $parts = $step -split ":", 2
    $verb = $parts[0].ToLowerInvariant()
    $rest = if ($parts.Count -gt 1) { $parts[1] } else { "" }

    switch ($verb) {
        "sleep" { Start-Sleep -Milliseconds ([int] $rest) }

        "key" {
            $a = $rest -split ":"
            $vk = Resolve-Vk $a[0]
            $mode = if ($a.Count -gt 1) { $a[1].ToLowerInvariant() } else { "tap" }
            if ($mode -eq "down") { [McInput]::Key($vk, $true) }
            elseif ($mode -eq "up") { [McInput]::Key($vk, $false) }
            else { [McInput]::Key($vk, $true); Start-Sleep -Milliseconds 40; [McInput]::Key($vk, $false) }
        }

        "type" {
            foreach ($ch in $rest.ToCharArray()) {
                [McInput]::Unicode($ch, $true); [McInput]::Unicode($ch, $false)
                Start-Sleep -Milliseconds 15
            }
        }

        "move" {
            $a = $rest -split ":"
            # Split into small steps: one large jump can be swallowed as a
            # discontinuity, and it reads as a teleport rather than a look.
            $dx = [int] $a[0]; $dy = [int] $a[1]
            $span = [Math]::Max([Math]::Abs($dx), [Math]::Abs($dy))
            # Minecraft's sensitivity runs around 30 pixels per degree here, so a
            # half turn is several thousand pixels and needs plenty of steps.
            $steps = [Math]::Max(1, [Math]::Min(120, [int]($span / 25)))
            for ($i = 1; $i -le $steps; $i++) {
                $px = [int]($dx * $i / $steps) - [int]($dx * ($i - 1) / $steps)
                $py = [int]($dy * $i / $steps) - [int]($dy * ($i - 1) / $steps)
                [McInput]::Mouse($MOVE, $px, $py, 0)
                Start-Sleep -Milliseconds 8
            }
        }

        "cmd" {
            # Chat keeps whatever was left in it, and a half-typed command from a
            # failed run silently prefixes the next one, so clear the box first.
            [McInput]::Key((Resolve-Vk "t"), $true); Start-Sleep -Milliseconds 40
            [McInput]::Key((Resolve-Vk "t"), $false); Start-Sleep -Milliseconds 400
            [McInput]::Key((Resolve-Vk "ctrl"), $true)
            [McInput]::Key((Resolve-Vk "a"), $true); Start-Sleep -Milliseconds 40
            [McInput]::Key((Resolve-Vk "a"), $false)
            [McInput]::Key((Resolve-Vk "ctrl"), $false); Start-Sleep -Milliseconds 80
            [McInput]::Key((Resolve-Vk "backspace"), $true); Start-Sleep -Milliseconds 40
            [McInput]::Key((Resolve-Vk "backspace"), $false); Start-Sleep -Milliseconds 120
            foreach ($ch in $rest.ToCharArray()) {
                [McInput]::Unicode($ch, $true); [McInput]::Unicode($ch, $false)
                Start-Sleep -Milliseconds 12
            }
            Start-Sleep -Milliseconds 250
            [McInput]::Key((Resolve-Vk "enter"), $true); Start-Sleep -Milliseconds 40
            [McInput]::Key((Resolve-Vk "enter"), $false); Start-Sleep -Milliseconds 350
        }

        "moveto" {
            # Client pixels, so a position read off a screenshot can be used directly.
            $a = $rest -split ":"
            $p = [McInput]::ClientPoint($hwnd, [int] $a[0], [int] $a[1])
            [McInput]::MoveTo($p.X, $p.Y)
            Start-Sleep -Milliseconds 60
        }

        "click" {
            $a = $rest -split ":"
            $button = $a[0].ToLowerInvariant()
            $mode = if ($a.Count -gt 1) { $a[1].ToLowerInvariant() } else { "tap" }
            $down = if ($button -eq "right") { $RDOWN } else { $LDOWN }
            $up   = if ($button -eq "right") { $RUP }   else { $LUP }
            if ($mode -eq "down") { [McInput]::Mouse($down, 0, 0, 0) }
            elseif ($mode -eq "up") { [McInput]::Mouse($up, 0, 0, 0) }
            else { [McInput]::Mouse($down, 0, 0, 0); Start-Sleep -Milliseconds 60; [McInput]::Mouse($up, 0, 0, 0) }
        }

        # The wheel delta is signed but travels in an unsigned field: pass its two's complement bits,
        # or scrolling down (a negative delta) fails to convert.
        "wheel" { [McInput]::Mouse($WHEEL, 0, 0, [BitConverter]::ToUInt32([BitConverter]::GetBytes([int] $rest * 120), 0)) }

        "shot" {
            $frame = Get-Frame (Get-CaptureRect "")
            Save-Scaled $frame $rest 1280
            $frame.Dispose()
            Write-Output "shot $rest"
        }

        "burst" {
            # count:interval:path[:region]; a Windows path has its own colon, so split from the left
            # and put the drive back together.
            $a = $rest -split ":"
            $count = [int] $a[0]; $interval = [int] $a[1]
            $tail = ($a[2..($a.Count - 1)] -join ":")
            $region = ""
            if ($tail -match "^(.*\.png):([0-9, x]+)$") { $tail = $Matches[1]; $region = $Matches[2] }
            $r = Get-CaptureRect $region
            $frames = New-Object System.Collections.Generic.List[System.Drawing.Bitmap]
            $clock = [System.Diagnostics.Stopwatch]::StartNew()
            for ($i = 0; $i -lt $count; $i++) {
                $due = $i * $interval
                $wait = $due - $clock.ElapsedMilliseconds
                if ($wait -gt 0) { Start-Sleep -Milliseconds $wait }
                $frames.Add((Get-Frame $r))
            }
            # A roughly screen-shaped grid, no wider than a screenshot, so it costs the same to look at.
            $columns = [Math]::Min($count, [Math]::Max(1, [int][Math]::Ceiling([Math]::Sqrt($count * 1.6 * $r.H / $r.W))))
            $rows = [int][Math]::Ceiling($count / $columns)
            $cellW = [int][Math]::Floor(1600 / $columns)
            $cellH = [int]($r.H * $cellW / $r.W)
            $sheetW = $cellW * $columns
            $sheetH = $cellH * $rows
            $sheet = New-Object System.Drawing.Bitmap($sheetW, $sheetH)
            $g = [System.Drawing.Graphics]::FromImage($sheet)
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.Clear([System.Drawing.Color]::Black)
            $font = New-Object System.Drawing.Font("Consolas", 11, [System.Drawing.FontStyle]::Bold)
            for ($i = 0; $i -lt $frames.Count; $i++) {
                $cx = ($i % $columns) * $cellW; $cy = [int][Math]::Floor($i / $columns) * $cellH
                $g.DrawImage($frames[$i], $cx, $cy, $cellW - 2, $cellH - 2)
                $label = "{0}  +{1}ms" -f $i, ($i * $interval)
                $g.DrawString($label, $font, [System.Drawing.Brushes]::Black, $cx + 5, $cy + 5)
                $g.DrawString($label, $font, [System.Drawing.Brushes]::Yellow, $cx + 4, $cy + 4)
                $frames[$i].Dispose()
            }
            $g.Dispose()
            Save-Scaled $sheet $tail 1600
            $sheet.Dispose()
            Write-Output ("burst {0} frames in {1} ms -> {2}" -f $count, $clock.ElapsedMilliseconds, $tail)
        }

        default { throw "Unknown step '$step'" }
    }
}

Write-Output "ok"
