param([string]$ApplicationDirectory = '')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskRuntime = Join-Path $taskRoot 'runtime'
$taskResults = [ordered]@{ timestamp=(Get-Date).ToUniversalTime().ToString('o'); scope='File inventory only; not clean-machine acceptance'; files=@() }
foreach ($taskRelative in @('models\eng.traineddata','models\chi_sim.traineddata','models\chi_tra.traineddata','models\LICENSE','opencv\opencv-4120.jar','opencv\opencv_java4120.dll','THIRD_PARTY_NOTICES.txt')) {
    $taskPath = Join-Path $taskRuntime $taskRelative
    if (-not (Test-Path -LiteralPath $taskPath -PathType Leaf)) { throw "Missing runtime resource: $taskRelative" }
    $taskResults.files += @{ path=$taskRelative; bytes=(Get-Item -LiteralPath $taskPath).Length; sha256=(Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant() }
}
if ($ApplicationDirectory) {
    $taskResolved = (Resolve-Path -LiteralPath $ApplicationDirectory).Path
    if (-not (Test-Path -LiteralPath (Join-Path $taskResolved 'PhoneForm AI PC.exe'))) { throw 'Application EXE missing' }
    if (-not (Test-Path -LiteralPath (Join-Path $taskResolved 'runtime\bin\java.exe'))) { throw 'Bundled java.exe missing (PDF helper requires it)' }
    $taskResults.application = $taskResolved
}
New-Item -ItemType Directory -Path (Join-Path $taskRoot 'output\validation') -Force | Out-Null
$taskResults | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $taskRoot 'output\validation\runtime-inventory.json') -Encoding UTF8
Write-Output 'Runtime inventory passed. Clean Windows install and native dependency verification are separate tests.'
