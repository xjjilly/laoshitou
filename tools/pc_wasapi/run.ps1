param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'WasapiTest.cs')
if ($null -eq $Rest) {
    $Rest = @()
}
exit [WasapiTest.Program]::Main($Rest)
