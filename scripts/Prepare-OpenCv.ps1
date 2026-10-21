param()
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDownload = Join-Path $taskRoot '.local\downloads\opencv-4.12.0-windows.exe'
$taskExtract = Join-Path $taskRoot '.local\opencv-4.12.0'
$taskRuntime = Join-Path $taskRoot 'runtime\opencv'
$taskExpectedSha = 'b753b14d880b9bc8d89d6acd3b665c040baec0211078435432fcae117db707af'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $taskDownload), $taskExtract, $taskRuntime | Out-Null
if (-not (Test-Path -LiteralPath $taskDownload)) {
    Invoke-WebRequest -Uri 'https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-windows.exe' -OutFile $taskDownload
}
if ((Get-FileHash -LiteralPath $taskDownload -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskExpectedSha) {
    throw 'OPENCV_DOWNLOAD_SHA256_MISMATCH'
}
$taskJarSource = Join-Path $taskExtract 'opencv\build\java\opencv-4120.jar'
if (-not (Test-Path -LiteralPath $taskJarSource)) {
    $taskProcess = Start-Process -FilePath $taskDownload -ArgumentList @("-o`"$taskExtract`"", '-y') -PassThru -Wait -WindowStyle Hidden
    if ($taskProcess.ExitCode -ne 0) { throw 'OPENCV_EXTRACTION_FAILED' }
}
Copy-Item -LiteralPath $taskJarSource -Destination (Join-Path $taskRuntime 'opencv-4120.jar')
Copy-Item -LiteralPath (Join-Path $taskExtract 'opencv\build\java\x64\opencv_java4120.dll') -Destination (Join-Path $taskRuntime 'opencv_java4120.dll')
$taskLicense = Get-ChildItem -LiteralPath (Join-Path $taskExtract 'opencv') -Recurse -File -Filter 'LICENSE' | Select-Object -First 1
if ($null -eq $taskLicense) { throw 'OPENCV_LICENSE_NOT_FOUND' }
Copy-Item -LiteralPath $taskLicense.FullName -Destination (Join-Path $taskRuntime 'LICENSE')
$taskManifest = [ordered]@{
    version = '4.12.0'
    platform = 'windows-x86_64'
    source = 'https://github.com/opencv/opencv/releases/tag/4.12.0'
    archiveSha256 = $taskExpectedSha
    files = @(Get-ChildItem -LiteralPath $taskRuntime -File | Where-Object Name -ne 'manifest.json' | ForEach-Object {
        [ordered]@{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
}
[IO.File]::WriteAllText((Join-Path $taskRuntime 'manifest.json'), ($taskManifest | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Output 'Verified official OpenCV 4.12.0 Java runtime installed in runtime/opencv.'
