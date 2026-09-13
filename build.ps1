# SPDX-License-Identifier: LicenseRef-Proprietary
# Copyright (C) 2026 Douglas J. Cerrato (KB2UKA) and Christian Suarez (N9WAR).
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $StationUrl,
    [Parameter(Mandatory)][string] $OutputDirectory,
    [string] $LanUrl,
    [string] $LanCertificate,
    [string] $DependencyCache,
    [string] $SdkRoot = $env:ANDROID_SDK_ROOT,
    [string] $BuildToolsVersion = '36.0.0',
    [string] $Platform = 'android-36'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Keep each configured station address and signing key outside the checkout.
$repositoryRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$pathComparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
if ($outputRoot.Equals($repositoryRoot, $pathComparison) -or
    $outputRoot.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, $pathComparison)) {
    throw 'OutputDirectory must be outside the repository.'
}
function Normalize-WatchUrl([string] $Address, [switch] $Local) {
    $stationUri = $null
    if (![Uri]::TryCreate($Address, [UriKind]::Absolute, [ref]$stationUri) -or
        $stationUri.Scheme -ne 'https' -or !$stationUri.Host -or $stationUri.UserInfo -or
        $Address -match '[\r\n]') {
        throw 'StationUrl and LanUrl must be absolute HTTPS addresses without embedded credentials.'
    }
    $urlBuilder = [UriBuilder]::new($stationUri)
    $queryParts = [Collections.Generic.List[string]]::new()
    foreach ($part in $urlBuilder.Query.TrimStart('?').Split('&', [StringSplitOptions]::RemoveEmptyEntries)) {
        $key = [Uri]::UnescapeDataString($part.Split('=', 2)[0].Replace('+', ' '))
        if ($key -notin @('watch', 'desktop', 'mobile') -and !($Local -and $key -eq 'remote')) { $queryParts.Add($part) }
    }
    $queryParts.Add('watch=1')
    $urlBuilder.Query = $queryParts -join '&'
    return $urlBuilder.Uri.AbsoluteUri
}
$normalizedUrl = Normalize-WatchUrl $StationUrl
if ([bool]$LanUrl -ne [bool]$LanCertificate) { throw 'LanUrl and LanCertificate must be supplied together.' }
$certificateBytes = $null
if ($LanUrl) {
    $normalizedLanUrl = Normalize-WatchUrl $LanUrl -Local
    $certificatePath = (Resolve-Path -LiteralPath $LanCertificate).Path
    $sourceBytes = [IO.File]::ReadAllBytes($certificatePath)
    $sourceText = [Text.Encoding]::ASCII.GetString($sourceBytes)
    if ($sourceText -match 'PRIVATE KEY') { throw 'LanCertificate must contain only a public certificate.' }
    if ([Security.Cryptography.X509Certificates.X509Certificate2]::GetCertContentType($sourceBytes) -ne [Security.Cryptography.X509Certificates.X509ContentType]::Cert) {
        throw 'LanCertificate must be a public X.509 certificate in DER or PEM format.'
    }
    $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($sourceBytes)
    try {
        if ($certificate.HasPrivateKey) { throw 'LanCertificate must not contain a private key.' }
        if ($certificate.NotBefore -gt [DateTime]::Now -or $certificate.NotAfter -lt [DateTime]::Now) { throw 'LanCertificate must be currently valid.' }
        $certificateBytes = $certificate.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert)
    } finally { $certificate.Dispose() }
}

# Third-party jars are pinned by SHA-256 in dependencies.json and cached outside
# the checkout. A jar whose hash does not match is deleted and re-fetched once;
# a second mismatch fails the build rather than shipping unknown code.
if (!$DependencyCache) { $DependencyCache = Join-Path $outputRoot 'dependencies' }
$dependencyRoot = [IO.Path]::GetFullPath($DependencyCache)
if ($dependencyRoot.Equals($repositoryRoot, $pathComparison) -or
    $dependencyRoot.StartsWith($repositoryRoot + [IO.Path]::DirectorySeparatorChar, $pathComparison)) {
    throw 'DependencyCache must be outside the repository.'
}
[IO.Directory]::CreateDirectory($dependencyRoot) | Out-Null
$libraryJars = [Collections.Generic.List[string]]::new()
foreach ($dependency in (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'dependencies.json') -Raw | ConvertFrom-Json)) {
    if ($dependency.file -match '[\\/]' -or !$dependency.url.StartsWith('https://repo.maven.apache.org/maven2/')) {
        throw "Refusing dependency $($dependency.file): unexpected file name or source."
    }
    $jar = Join-Path $dependencyRoot $dependency.file
    $expected = $dependency.sha256.ToUpperInvariant()
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        if (Test-Path -LiteralPath $jar) {
            if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -eq $expected) { break }
            Remove-Item -LiteralPath $jar -Force
        }
        if ($attempt -ge 1) { break }
        Invoke-WebRequest -Uri $dependency.url -OutFile $jar -MaximumRedirection 2 -UseBasicParsing
    }
    if (!(Test-Path -LiteralPath $jar) -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $expected) {
        throw "Dependency $($dependency.file) does not match its pinned SHA-256."
    }
    $libraryJars.Add($jar)
}
$libraryClasspath = $libraryJars -join [IO.Path]::PathSeparator

if (!$SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (!$SdkRoot -and $IsWindows) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!$SdkRoot) { throw 'Set ANDROID_SDK_ROOT or pass -SdkRoot.' }
$buildTools = Join-Path $SdkRoot "build-tools/$BuildToolsVersion"
$androidJar = Join-Path $SdkRoot "platforms/$Platform/android.jar"
if (!(Test-Path -LiteralPath $androidJar)) { throw "Android platform missing: $androidJar" }

function Get-SdkTool([string] $Name, [switch] $JavaTool) {
    $suffix = if ($IsWindows) { if ($JavaTool) { '.bat' } else { '.exe' } } else { '' }
    $tool = Join-Path $buildTools ($Name + $suffix)
    if (!(Test-Path -LiteralPath $tool)) { throw "Android build tool missing: $tool" }
    return $tool
}
function Invoke-BuildTool([string] $Executable, [string[]] $Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}
$aapt2 = Get-SdkTool 'aapt2'
$d8 = Get-SdkTool 'd8' -JavaTool
$zipalign = Get-SdkTool 'zipalign'
$apksigner = Get-SdkTool 'apksigner' -JavaTool
$javac = (Get-Command javac -ErrorAction Stop).Source
$keytool = (Get-Command keytool -ErrorAction Stop).Source
$buildRoot = Join-Path $outputRoot ('build-' + [Guid]::NewGuid().ToString('N'))
$resourceRoot = Join-Path $buildRoot 'res'
$assetsRoot = Join-Path $buildRoot 'assets'
$classesRoot = Join-Path $buildRoot 'classes'
$dexRoot = Join-Path $buildRoot 'dex'
foreach ($directory in @($outputRoot, $buildRoot, $resourceRoot, (Join-Path $resourceRoot 'drawable'), $assetsRoot, $classesRoot, $dexRoot)) {
    [IO.Directory]::CreateDirectory($directory) | Out-Null
}
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'assets/zeus-app-icon.png') -Destination (Join-Path $resourceRoot 'drawable/zeus_app_icon.png') -Force
[IO.File]::WriteAllText((Join-Path $assetsRoot 'station-url.txt'), $normalizedUrl, [Text.UTF8Encoding]::new($false))
# The native UI reads its palette from the same tokens file the web client uses,
# so the watch can never drift into its own hard-coded colours.
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'assets/design-tokens.css') -Destination (Join-Path $assetsRoot 'design-tokens.css') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'THIRD-PARTY-NOTICES.txt') -Destination (Join-Path $assetsRoot 'third-party-notices.txt') -Force
if ($LanUrl) {
    [IO.File]::WriteAllText((Join-Path $assetsRoot 'lan-url.txt'), $normalizedLanUrl, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllBytes((Join-Path $assetsRoot 'lan-certificate.der'), $certificateBytes)
}
$compiledResources = Join-Path $buildRoot 'resources.zip'
$unsignedApk = Join-Path $buildRoot 'unsigned.apk'
$alignedApk = Join-Path $buildRoot 'aligned.apk'
$apkPath = Join-Path $outputRoot 'zeus-watch.apk'
Invoke-BuildTool $aapt2 @('compile', '--dir', $resourceRoot, '-o', $compiledResources)
Invoke-BuildTool $aapt2 @('link', '-o', $unsignedApk, '-I', $androidJar, '--manifest', (Join-Path $PSScriptRoot 'AndroidManifest.xml'), '-A', $assetsRoot, $compiledResources)
$javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$compileClasspath = ($androidJar, $libraryClasspath | Where-Object { $_ }) -join [IO.Path]::PathSeparator
Invoke-BuildTool $javac (@('-source', '8', '-target', '8', '-Xlint:-options', '-classpath', $compileClasspath, '-d', $classesRoot) + $javaFiles)
$classFiles = @(Get-ChildItem -LiteralPath $classesRoot -Recurse -Filter '*.class' | ForEach-Object FullName)
Invoke-BuildTool $d8 (@('--min-api', '26', '--lib', $androidJar, '--output', $dexRoot) + $classFiles + $libraryJars)
$dexFiles = @(Get-ChildItem -LiteralPath $dexRoot -Filter 'classes*.dex' | Sort-Object Name)
if ($dexFiles.Count -eq 0) { throw 'd8 produced no dex output.' }
$archive = [IO.Compression.ZipFile]::Open($unsignedApk, [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($dex in $dexFiles) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $dex.FullName, $dex.Name) | Out-Null
    }
} finally { $archive.Dispose() }
Invoke-BuildTool $zipalign @('-f', '4', $unsignedApk, $alignedApk)
$keystore = Join-Path $outputRoot 'zeus-watch-debug.keystore'
if (!(Test-Path -LiteralPath $keystore)) {
    Invoke-BuildTool $keytool @('-genkeypair', '-noprompt', '-keystore', $keystore, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'zeus-watch-debug', '-dname', 'CN=Zeus Watch Development', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000')
}
Invoke-BuildTool $apksigner @('sign', '--ks', $keystore, '--ks-key-alias', 'zeus-watch-debug', '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--out', $apkPath, $alignedApk)
Invoke-BuildTool $apksigner @('verify', '--verbose', $apkPath)
Invoke-BuildTool $zipalign @('-c', '4', $apkPath)
Write-Output "APK: $apkPath"
Write-Output "SHA256: $((Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash)"
