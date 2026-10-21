param([string[]]$Tasks = @('test', ':desktop:packageMsi'), [string]$JdkPath = '')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkPath) {
    $taskBundledJdk = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.local\jdk21') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    $JdkPath = if ($taskBundledJdk) { $taskBundledJdk.FullName } else { Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
}
if (-not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin\java.exe'))) { throw "JDK 21 not found: $JdkPath" }
$taskOldJava = $env:JAVA_HOME
$taskOldGradle = $env:GRADLE_USER_HOME
$taskOldOptions = $env:JAVA_TOOL_OPTIONS
Push-Location $taskRoot
try {
    $env:JAVA_HOME = $JdkPath
    $env:GRADLE_USER_HOME = Join-Path $taskRoot '.local\gradle'
    $taskTemp = Join-Path $taskRoot '.local\tmp'
    New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
    $env:JAVA_TOOL_OPTIONS = ($taskOldOptions + " `"-Djava.io.tmpdir=$taskTemp`" `"-Djdk.net.unixdomain.tmpdir=$taskTemp\unavailable-unix`"").Trim()
    $taskLocalGradle = Join-Path $taskRoot '.local\gradle-runtime\bin\gradle.bat'
    if (Test-Path -LiteralPath $taskLocalGradle) { & $taskLocalGradle @Tasks --no-daemon --console=plain }
    else { & (Join-Path $taskRoot 'gradlew.bat') @Tasks --no-daemon --console=plain }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location; $env:JAVA_HOME = $taskOldJava; $env:GRADLE_USER_HOME = $taskOldGradle; $env:JAVA_TOOL_OPTIONS = $taskOldOptions }
