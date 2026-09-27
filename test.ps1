# SPDX-License-Identifier: LicenseRef-Proprietary
# Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
[CmdletBinding()]
param([Parameter(Mandatory)][string] $OutputDirectory)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($PSScriptRoot)
$output = [IO.Path]::GetFullPath($OutputDirectory)
if ($output.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or $output.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'OutputDirectory must be outside the repository.' }
$output = Join-Path $output ('run-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($output) | Out-Null
foreach ($name in @('server', 'different')) {
    $key = Join-Path $output ($name + '.p12')
    if (!(Test-Path -LiteralPath $key)) {
        & keytool -genkeypair -noprompt -alias test -keyalg RSA -keystore $key -storetype PKCS12 -storepass changeit -keypass changeit -dname 'CN=Probe Test' -ext 'SAN=ip:127.0.0.1' -validity 2
        if ($LASTEXITCODE -ne 0) { throw 'Test certificate generation failed.' }
    }
}
# The server key re-issued under a new self-signed certificate, as the station
# does when its LAN addresses change.
$reissued = Join-Path $output 'reissued.p12'
Copy-Item -LiteralPath (Join-Path $output 'server.p12') -Destination $reissued
& keytool -selfcert -noprompt -alias test -keystore $reissued -storetype PKCS12 -storepass changeit -keypass changeit -dname 'CN=Probe Test Reissued' -validity 2
if ($LASTEXITCODE -ne 0) { throw 'Re-issued test certificate generation failed.' }
# Android-free sources only: these run on a plain JVM, no emulator or device.
$sources = @(
    'src/org/openhpsdr/zeus/watch/PinnedTls.java',
    'src/org/openhpsdr/zeus/watch/LanProbe.java',
    'src/org/openhpsdr/zeus/watch/WireFrames.java',
    'src/org/openhpsdr/zeus/watch/PaletteParser.java',
    'src/org/openhpsdr/zeus/watch/TuningModel.java',
    'src/org/openhpsdr/zeus/watch/TouchRouter.java',
    'src/org/openhpsdr/zeus/watch/WaterfallPalette.java',
    'src/org/openhpsdr/zeus/watch/WristFlip.java',
    'tests/LanProbeTest.java',
    'tests/WatchNativeTest.java'
) | ForEach-Object { Join-Path $PSScriptRoot $_ }
& javac -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& java -cp $output org.openhpsdr.zeus.watch.LanProbeTest (Join-Path $output 'server.p12') (Join-Path $output 'different.p12') $reissued
if ($LASTEXITCODE -ne 0) { throw 'LAN probe tests failed.' }
& java -cp $output org.openhpsdr.zeus.watch.WatchNativeTest (Join-Path $root 'assets/design-tokens.css')
if ($LASTEXITCODE -ne 0) { throw 'Native watch tests failed.' }
