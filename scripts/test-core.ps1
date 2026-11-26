param([string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome) { throw 'Provide -JdkHome with a JDK 17+ path, or set JAVA_HOME.' }
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javac)) { throw "JDK compiler not found: $javac" }
$output = Join-Path $projectRoot 'core/build/test-classes'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'core/src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
& $javac --release 17 -encoding UTF-8 -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed.' }
& $java -cp $output com.camerax9p.core.CoreTests
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed.' }
