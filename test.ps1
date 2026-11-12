$ErrorActionPreference = "Stop"

& "$PSScriptRoot\build.ps1"
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$testOutputDirectory = Join-Path $PSScriptRoot "build\test-classes"
New-Item -ItemType Directory -Force -Path $testOutputDirectory | Out-Null
$testFiles = Get-ChildItem (Join-Path $PSScriptRoot "src\test\java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }

javac --release 21 -cp "$PSScriptRoot\build\classes" -d $testOutputDirectory $testFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

java -cp "$PSScriptRoot\build\classes;$testOutputDirectory" com.mphasis.txprocessor.TransactionProcessorTest
exit $LASTEXITCODE