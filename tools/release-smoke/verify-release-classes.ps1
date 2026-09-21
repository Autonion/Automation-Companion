param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$Mapping,
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Jdk = "C:/Program Files/Android/Android Studio/jbr",
    [string]$BuildTools = "36.1.0",
    [string[]]$Classes = @(
        'org.java_websocket.server.WebSocketServer',
        'com.autonion.automationcompanion.features.semantic_automation.core.ExtensionBridgeServer'
    )
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$mappingPath = (Resolve-Path -LiteralPath $Mapping).Path
$runId = [Guid]::NewGuid().ToString('N')
$output = Join-Path $repo "build/release-smoke/$runId"
$remote = "/data/local/tmp/autonion-r8-smoke-$runId"
$adb = Join-Path $Sdk 'platform-tools/adb.exe'

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

# Resolve names from this artifact's mapping instead of hard-coding names from a crash.
$mapped = @{}
$mappingHeader = Get-Content -LiteralPath $mappingPath -TotalCount 12
$mapIdLine = $mappingHeader | Where-Object { $_ -match '^# pg_map_id: ' } | Select-Object -First 1
if (!$mapIdLine) { throw 'The mapping is missing its R8 map ID' }
$mapId = $mapIdLine.Substring('# pg_map_id: '.Length).Trim()
$wanted = '^(' + (($Classes | ForEach-Object { [Regex]::Escape($_) }) -join '|') + ') -> ([\w.$]+):$'
Select-String -LiteralPath $mappingPath -Pattern $wanted | ForEach-Object {
    $match = [Regex]::Match($_.Line, $wanted)
    $mapped[$match.Groups[1].Value] = $match.Groups[2].Value
}
foreach ($className in $Classes) {
    if (!$mapped.ContainsKey($className)) { throw "No mapping found for $className" }
    Write-Output "$className -> $($mapped[$className])"
}

New-Item -ItemType Directory -Force "$output/classes", "$output/dex" | Out-Null
Invoke-Checked "$Jdk/bin/javac.exe" @('--release', '8', '-d', "$output/classes", "$PSScriptRoot/ReleaseClassVerifier.java")
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $Jdk
    Invoke-Checked "$Sdk/build-tools/$BuildTools/d8.bat" @('--min-api', '24', '--output', "$output/dex", "$output/classes/ReleaseClassVerifier.class")
} finally {
    $env:JAVA_HOME = $previousJavaHome
}
Invoke-Checked "$Jdk/bin/jar.exe" @('cf', "$output/verifier.jar", '-C', "$output/dex", 'classes.dex')

# Copy only DEX: no APK installation, Activity launch, server start, or app-data access.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$source = [IO.Compression.ZipFile]::OpenRead($apkPath)
$target = [IO.Compression.ZipFile]::Open("$output/release-dex.zip", [IO.Compression.ZipArchiveMode]::Create)
$foundMatchingId = $false
try {
    foreach ($entry in $source.Entries) {
        if ($entry.FullName -notmatch '^classes([0-9]+)?\.dex$') { continue }
        $copy = $target.CreateEntry($entry.FullName)
        $inputStream = $entry.Open()
        $outputStream = $copy.Open()
        try {
            $buffer = [IO.MemoryStream]::new()
            try {
                $inputStream.CopyTo($buffer)
                $bytes = $buffer.ToArray()
                $text = [Text.Encoding]::ASCII.GetString($bytes)
                foreach ($marker in [Regex]::Matches($text, '~~R8(\{[^\x00]+\})')) {
                    $metadata = $marker.Groups[1].Value | ConvertFrom-Json
                    if ($metadata.'pg-map-id' -ne $mapId) { throw 'APK DEX and mapping have different R8 map IDs' }
                    $foundMatchingId = $true
                }
                $outputStream.Write($bytes, 0, $bytes.Length)
            } finally { $buffer.Dispose() }
        }
        finally { $inputStream.Dispose(); $outputStream.Dispose() }
    }
} finally {
    $target.Dispose()
    $source.Dispose()
}
if (!$foundMatchingId) { throw 'Could not confirm the APK DEX belongs to this R8 mapping' }

Invoke-Checked $adb @('-s', $Serial, 'shell', 'mkdir', '-p', $remote)
try {
    Invoke-Checked $adb @('-s', $Serial, 'push', "$output/verifier.jar", "$output/release-dex.zip", "$remote/")
    Invoke-Checked $adb @('-s', $Serial, 'shell', 'chmod', '444', "$remote/verifier.jar", "$remote/release-dex.zip")
    # Quote mapped inner-class names for the device shell (they may contain $).
    $names = @($Classes | ForEach-Object { "'" + $mapped[$_] + "'" })
    $arguments = @('-s', $Serial, 'shell', 'dalvikvm', '-Xverify:all', '-cp',
        "$remote/verifier.jar:$remote/release-dex.zip", 'ReleaseClassVerifier') + $names
    & $adb @arguments 2>&1 | Tee-Object -FilePath "$output/verification.log"
    $verificationExit = $LASTEXITCODE
    Write-Output "Verification log: $output/verification.log"
    if ($verificationExit -ne 0) { throw "ART class verification failed (exit $verificationExit)" }
} finally {
    # Only remove this run's two temporary files and its empty directory.
    if ($remote -notmatch '^/data/local/tmp/autonion-r8-smoke-[a-f0-9]{32}$') { throw 'Invalid temporary device path' }
    & $adb -s $Serial shell rm -f "$remote/verifier.jar" "$remote/release-dex.zip"
    & $adb -s $Serial shell rmdir $remote
}
