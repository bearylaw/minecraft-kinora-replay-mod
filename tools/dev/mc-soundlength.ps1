<#
.SYNOPSIS
    How long a Minecraft sound actually is, in seconds.

.DESCRIPTION
    Resolves a sound event through the game's own sounds.json, finds every file behind
    it in the asset store, and reads each file's length out of its Ogg headers. No
    guessing, and no need to open the game.

    This exists because "that sound is too long" is not actionable and "that sound is
    2.95 seconds and you fire three times a second" is. It is what settled the portal
    gun's palette - see the Sound section of the repository README.

    A sound event can have several files behind it, which the game picks between at
    random; all of them are listed, because the longest is the one that matters.

.PARAMETER Event
    Sound event ids, as they appear in SoundEvents - block.beacon.activate,
    entity.ender_eye.launch. Names with no dot are treated as file paths instead
    (block/beacon/activate), which is how to look at a file the events do not name.

.PARAMETER Index
    Asset index to read, without the .json. Defaults to the highest-numbered one,
    which is the newest installed. Pass this if several versions are installed and
    the newest is not the one being worked on.

.EXAMPLE
    tools\dev\mc-soundlength.ps1 block.beacon.activate block.portal.trigger

.EXAMPLE
    tools\dev\mc-soundlength.ps1 -Event entity.enderman.teleport
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Event,
    [string] $Index
)

$ErrorActionPreference = 'Stop'

# Seconds are printed with a dot wherever this runs. PowerShell formats decimals with the
# local separator, which on a German system makes 2.95 read as "2,95" - harmless here, but
# this repository has already lost time to that separator reaching Minecraft's command
# parser, so nothing in tools/dev prints a number the local way.
$invariant = [System.Globalization.CultureInfo]::InvariantCulture

$assets = Join-Path $env:APPDATA '.minecraft\assets'
if (-not (Test-Path $assets)) {
    Write-Error "No asset store at $assets. Has the game been run from this launcher?"
    return
}

# ---------------------------------------------------------------- the index

if ($Index) {
    $indexPath = Join-Path $assets "indexes\$Index.json"
} else {
    # Highest number wins: the indexes are named for the asset generation, and the
    # newest install has the highest. Sorted numerically, not as text, or 9 beats 32.
    $indexPath = Get-ChildItem (Join-Path $assets 'indexes') -Filter *.json |
        Sort-Object { [int]($_.BaseName -replace '\D', '0') } |
        Select-Object -Last 1 -ExpandProperty FullName
}
if (-not $indexPath -or -not (Test-Path $indexPath)) {
    Write-Error "No such asset index: $indexPath"
    return
}
Write-Verbose "index $indexPath"

$objects = @{}
foreach ($property in (Get-Content $indexPath -Raw | ConvertFrom-Json).objects.PSObject.Properties) {
    $objects[$property.Name] = $property.Value.hash
}

function Get-AssetPath([string] $key) {
    $hash = $objects[$key]
    if (-not $hash) { return $null }
    return Join-Path $assets ("objects\" + $hash.Substring(0, 2) + "\" + $hash)
}

# ---------------------------------------------------------------- events to files

# sounds.json is in the asset store rather than the jar, and is itself hashed.
$soundsPath = Get-AssetPath 'minecraft/sounds.json'
if (-not $soundsPath) {
    Write-Error 'This asset index has no minecraft/sounds.json.'
    return
}
$sounds = Get-Content $soundsPath -Raw | ConvertFrom-Json

function Get-EventFiles([string] $name) {
    $entry = $sounds.PSObject.Properties[$name]
    if (-not $entry) { return $null }
    $files = New-Object System.Collections.Generic.List[string]
    foreach ($sound in $entry.Value.sounds) {
        if ($sound -is [string]) {
            $files.Add(($sound -replace '^minecraft:', ''))
            continue
        }
        # An entry can name another event rather than a file, and then means all of it.
        if ($sound.type -eq 'event') {
            $nested = Get-EventFiles ($sound.name -replace '^minecraft:', '')
            if ($nested) { $files.AddRange($nested) }
        } else {
            $files.Add(($sound.name -replace '^minecraft:', ''))
        }
    }
    return $files
}

# ---------------------------------------------------------------- reading a file

function Get-OggSeconds([string] $path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    # Latin-1 keeps one byte one character, so a page marker can be found by index.
    $text = [System.Text.Encoding]::GetEncoding(28591).GetString($bytes)

    # Sample rate lives in the identification header, in the first page whose body
    # starts with 0x01 "vorbis".
    $rate = 0
    $at = $text.IndexOf('OggS')
    while ($at -ge 0 -and $rate -eq 0) {
        $segments = $bytes[$at + 26]
        $body = $at + 27 + $segments
        if ($body + 16 -lt $bytes.Length -and $bytes[$body] -eq 1 `
                -and $text.Substring($body + 1, 6) -eq 'vorbis') {
            $rate = [System.BitConverter]::ToUInt32($bytes, $body + 12)
        }
        $at = $text.IndexOf('OggS', $at + 4)
    }
    if ($rate -eq 0) { return $null }

    # The last page's granule position is the total number of samples.
    $last = $text.LastIndexOf('OggS')
    $granule = [System.BitConverter]::ToInt64($bytes, $last + 6)
    return $granule / $rate
}

# ---------------------------------------------------------------- report

if (-not $Event) {
    Write-Error 'Name at least one sound event, e.g. block.beacon.activate'
    return
}

foreach ($name in $Event) {
    $files = $null
    if ($name -like '*.*') { $files = Get-EventFiles $name }
    if (-not $files) {
        # Either not an event, or given as a file path in the first place.
        $files = @($name -replace '\.', '/')
    }

    $lengths = @()
    foreach ($file in $files) {
        $path = Get-AssetPath ("minecraft/sounds/$file.ogg")
        if (-not $path) {
            Write-Host ("    {0,-48} not in this index" -f $file)
            continue
        }
        $seconds = Get-OggSeconds $path
        $lengths += $seconds
        Write-Host ([string]::Format($invariant, "    {0,-48} {1,5:N2} s", $file, $seconds))
    }

    if ($lengths.Count -eq 0) {
        Write-Host ("{0} - no such event, and no such file" -f $name)
        continue
    }
    $span = if ($lengths.Count -eq 1) {
        [string]::Format($invariant, '{0:N2} s', $lengths[0])
    } else {
        [string]::Format($invariant, '{0:N2}-{1:N2} s',
            ($lengths | Measure-Object -Minimum).Minimum,
            ($lengths | Measure-Object -Maximum).Maximum)
    }
    Write-Host ("{0,-44} {1,2} file(s)  {2}" -f $name, $lengths.Count, $span)
}
