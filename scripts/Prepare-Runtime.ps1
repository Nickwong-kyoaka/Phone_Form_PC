param([switch]$SkipJdk)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $SkipJdk) {
    $taskExisting = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.local\jdk21') -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\jpackage.exe') } | Select-Object -First 1
    if (-not $taskExisting) {
        New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot '.local') | Out-Null
        $taskRelease = (Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse')[0]
        $taskPackage = $taskRelease.binary.package
        $taskZip = Join-Path $taskRoot '.local\jdk21.zip'
        Invoke-WebRequest -Uri $taskPackage.link -OutFile $taskZip
        if ((Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskPackage.checksum) { throw 'JDK checksum mismatch' }
        Expand-Archive -LiteralPath $taskZip -DestinationPath (Join-Path $taskRoot '.local\jdk21') -Force
        @{ release=$taskRelease.release_name; source=$taskPackage.link; sha256=$taskPackage.checksum } | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $taskRoot '.local\jdk21-source.json')
    }
}
& (Join-Path $PSScriptRoot 'Prepare-OpenCv.ps1')
& (Join-Path $PSScriptRoot 'Prepare-Ocr.ps1')
Write-Output 'Build resources are ready. Run scripts/Invoke-Build.ps1 to test and build MSI.'
