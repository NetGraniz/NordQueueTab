param(
    [string]$ProxyPath = 'Z:\Minecraft Proxy',
    [string]$QueueJar = (Join-Path $PSScriptRoot '..\NordQueue\releases\1.1.1\NordQueue-1.1.1.jar')
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourcePath = Join-Path $projectPath 'src\main\java'
$resourcePath = Join-Path $projectPath 'src\main\resources'
$buildPath = Join-Path $projectPath 'build'
$classesPath = Join-Path $buildPath 'classes'
$outputPath = Join-Path $buildPath 'NordQueueTab-1.1.0.jar'
$velocityJar = Join-Path $ProxyPath 'velocity.jar'
$javaPath = 'C:\Program Files\Java\jdk-25\bin'

if (-not (Test-Path -LiteralPath $velocityJar)) { throw "Velocity jar not found: $velocityJar" }
if (-not (Test-Path -LiteralPath $QueueJar)) { throw "NordQueue 1.1.1 dependency not found: $QueueJar" }
New-Item -ItemType Directory -Force -Path $classesPath | Out-Null
$resolvedClasses = (Resolve-Path -LiteralPath $classesPath).Path
$resolvedProject = (Resolve-Path -LiteralPath $projectPath).Path
if ($resolvedClasses -ne (Join-Path $resolvedProject 'build\classes')) { throw 'Unsafe classes cleanup target' }
foreach ($child in Get-ChildItem -LiteralPath $resolvedClasses -Force) {
    if (-not $child.FullName.StartsWith($resolvedClasses + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe cleanup child' }
    Remove-Item -LiteralPath $child.FullName -Recurse -Force
}
$sources = Get-ChildItem -LiteralPath $sourcePath -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
$classpath = "$velocityJar;$QueueJar"
& (Join-Path $javaPath 'javac.exe') --release 25 -encoding UTF-8 -classpath $classpath -d $classesPath $sources
if ($LASTEXITCODE -ne 0) { throw 'NordQueueTab compilation failed.' }
$testPath = Join-Path $projectPath 'src\test\java'
$testClasses = Join-Path $buildPath 'test-classes'
New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
$tests = Get-ChildItem -LiteralPath $testPath -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
& (Join-Path $javaPath 'javac.exe') --release 25 -encoding UTF-8 -classpath "$classesPath;$classpath" -d $testClasses $tests
if ($LASTEXITCODE -ne 0) { throw 'NordQueueTab test compilation failed.' }
& (Join-Path $javaPath 'java.exe') -classpath "$testClasses;$classesPath;$classpath" com.nordfjell.nordqueuetab.QueueTabTest
if ($LASTEXITCODE -ne 0) { throw 'NordQueueTab tests failed.' }
Copy-Item -Path (Join-Path $resourcePath '*') -Destination $classesPath -Recurse -Force
if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath -Force }
Push-Location $classesPath
try {
    & (Join-Path $javaPath 'jar.exe') --create --file $outputPath .
    if ($LASTEXITCODE -ne 0) { throw 'NordQueueTab packaging failed.' }
} finally { Pop-Location }
Write-Output $outputPath
