$ErrorActionPreference = "Stop"

& "$PSScriptRoot\build.ps1"
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

java -cp "$PSScriptRoot\build\classes" com.mphasis.txprocessor.Main @args
exit $LASTEXITCODE