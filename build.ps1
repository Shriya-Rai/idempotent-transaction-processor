$ErrorActionPreference = "Stop"

$outputDirectory = Join-Path $PSScriptRoot "build\classes"
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$sourceFiles = Get-ChildItem (Join-Path $PSScriptRoot "src\main\java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }

javac --release 21 -d $outputDirectory @sourceFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
Write-Output "Build succeeded."