param()
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskModels = Join-Path $taskRoot 'runtime\models'
$taskCommit = 'e12c65a915945e4c28e237a9b52bc4a8f39a0cec'
$taskExpected = [ordered]@{
    'eng.traineddata' = '176dc3220de7db34d3b3aecbfa42043a6038348b'
    'chi_sim.traineddata' = 'da7fa49ded2895ebe974c6ea3a6cc1af8595f1af'
    'chi_tra.traineddata' = 'c1dd109cd5bbec27381f578e9a425cef88466244'
    'LICENSE' = 'd9a10c0d8e868ebf8da0b3dc95bb0be634c34bfe'
}
function Get-ModelGitBlobHash([string]$Path) {
    $taskBytes = [IO.File]::ReadAllBytes($Path)
    $taskPrefix = [Text.Encoding]::ASCII.GetBytes("blob $($taskBytes.Length)`0")
    $taskHash = [Security.Cryptography.SHA1]::Create()
    try { return -join ($taskHash.ComputeHash([byte[]]($taskPrefix + $taskBytes)) | ForEach-Object { $_.ToString('x2') }) }
    finally { $taskHash.Dispose() }
}
New-Item -ItemType Directory -Force -Path $taskModels | Out-Null
foreach ($taskEntry in $taskExpected.GetEnumerator()) {
    $taskFile = Join-Path $taskModels $taskEntry.Key
    if (-not (Test-Path -LiteralPath $taskFile) -or (Get-ModelGitBlobHash $taskFile) -ne $taskEntry.Value) {
        $taskPartial = "$taskFile.partial"
        Invoke-WebRequest -Uri "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/$taskCommit/$($taskEntry.Key)" -OutFile $taskPartial
        if ((Get-ModelGitBlobHash $taskPartial) -ne $taskEntry.Value) { throw "OCR_MODEL_HASH_MISMATCH:$($taskEntry.Key)" }
        Move-Item -LiteralPath $taskPartial -Destination $taskFile -Force
    }
}
$taskManifest = [ordered]@{
    source = 'https://github.com/tesseract-ocr/tessdata_best'
    commit = $taskCommit
    engine = 'Tesseract LSTM, tessdata_best'
    files = @($taskExpected.GetEnumerator() | ForEach-Object {
        [ordered]@{ name = $_.Key; gitBlobSha1 = $_.Value; sha256 = (Get-FileHash -LiteralPath (Join-Path $taskModels $_.Key) -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
}
[IO.File]::WriteAllText((Join-Path $taskModels 'manifest.json'), ($taskManifest | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Output 'Verified pinned English, simplified Chinese and traditional Chinese OCR models in runtime/models.'
