# Requires PowerShell 7.4+ with Test-Json (JSON Schema draft 2020-12 support).
# Validate the documented snapshot fixtures and important rejection boundaries.
$ErrorActionPreference = 'Stop'
$schemaPath = Join-Path $PSScriptRoot 'dental-chart.schema.json'
$exampleDirectory = Join-Path $PSScriptRoot 'examples'
$fixturePaths = Get-ChildItem -LiteralPath $exampleDirectory -Filter '*.json'
$passed = 0

foreach ($fixturePath in $fixturePaths) {
    $jsonText = Get-Content -LiteralPath $fixturePath.FullName -Raw
    if (-not (Test-Json -Json $jsonText -SchemaFile $schemaPath)) {
        throw "Invalid fixture: $($fixturePath.Name)"
    }
    $snapshot = ConvertFrom-Json -InputObject $jsonText -AsHashtable
    $compact = ConvertTo-Json -InputObject $snapshot -Depth 30 -Compress
    $byteCount = [System.Text.Encoding]::UTF8.GetByteCount($compact)
    if ($byteCount -gt 32768) { throw "Snapshot exceeds 32 KiB: $($fixturePath.Name)" }
    Write-Output "PASS fixture $($fixturePath.Name) ($byteCount bytes compact UTF-8)"
    $passed++
}

$seed = Get-Content -LiteralPath (Join-Path $exampleDirectory 'chart.permanent.sparse.json') -Raw
$invalidCases = [ordered]@{
    'unsupported schema' = { param($chart) $chart.schemaVersion = 3 }
    'unsupported catalogue' = { param($chart) $chart.catalogVersion = 'fdi-v2' }
    'wrong numbering system' = { param($chart) $chart.numberingSystem = 'universal' }
    'invalid dentition' = { param($chart) $chart.dentition = 'adult' }
    'invalid FDI 19' = { param($chart) $chart.teeth['19'] = $chart.teeth['11']; $chart.teeth.Remove('11') | Out-Null }
    'invalid primary FDI 56' = { param($chart) $chart.dentition = 'mixed'; $chart.teeth['56'] = $chart.teeth['11'] }
    'primary slot in permanent inventory' = { param($chart) $chart.teeth['51'] = $chart.teeth['11'] }
    'permanent slot in primary inventory' = { param($chart) $chart.dentition = 'primary' }
    'occlusal on permanent incisor' = { param($chart) $chart.teeth['11'].surfaces['O'] = $chart.teeth['11'].surfaces['F'] }
    'incisal on permanent molar' = { param($chart) $chart.teeth['16'].surfaces['I'] = $chart.teeth['16'].surfaces['O'] }
    'occlusal on primary canine' = { param($chart) $chart.dentition = 'mixed'; $chart.teeth['53'] = @{ assessment = 'assessed'; presence = 'present'; surfaces = @{ O = @{ assessment = 'assessed'; findings = @(); restorations = @() } } } }
    'incisal on primary molar' = { param($chart) $chart.dentition = 'mixed'; $chart.teeth['54'] = @{ assessment = 'assessed'; presence = 'present'; surfaces = @{ I = @{ assessment = 'assessed'; findings = @(); restorations = @() } } } }
    'unknown surface' = { param($chart) $chart.teeth['11'].surfaces['X'] = $chart.teeth['11'].surfaces['F'] }
    'missing assessment' = { param($chart) $chart.teeth['11'].Remove('assessment') | Out-Null }
    'missing presence' = { param($chart) $chart.teeth['11'].Remove('presence') | Out-Null }
    'missing surface observations' = { param($chart) $chart.teeth['11'].surfaces['F'].Remove('findings') | Out-Null }
    'unknown presence' = { param($chart) $chart.teeth['48'].presence = 'healthy' }
    'unknown clinical code' = { param($chart) $chart.teeth['16'].surfaces['O'].findings = @('healthy') }
    'duplicate code' = { param($chart) $chart.teeth['16'].surfaces['O'].findings = @('caries', 'caries') }
    'unbounded note field' = { param($chart) $chart.teeth['11'].notes = 'Not permitted in snapshot' }
    'duplicated tooth identity' = { param($chart) $chart.teeth['11'].toothId = 'FDI-11' }
    'null instead of omission' = { param($chart) $chart.teeth['11'].findings = $null }
    'timestamp placeholder' = { param($chart) $chart.updatedAt = 'Firestore Timestamp' }
    'missing context' = { param($chart) $chart.Remove('patientId') | Out-Null }
    'negative revision' = { param($chart) $chart.chartVersion = -1 }
    'noninteger revision' = { param($chart) $chart.chartVersion = 1.5 }
    'completed as open treatment state' = { param($chart) $chart.teeth['16'].treatmentStates = @('completed') }
}

foreach ($caseName in $invalidCases.Keys) {
    $chart = ConvertFrom-Json -InputObject $seed -AsHashtable
    & $invalidCases[$caseName] $chart
    $candidateJson = ConvertTo-Json -InputObject $chart -Depth 30 -Compress
    $accepted = Test-Json -Json $candidateJson -SchemaFile $schemaPath -ErrorAction SilentlyContinue
    if ($accepted) { throw "Schema unexpectedly accepted: $caseName" }
    Write-Output "PASS rejection: $caseName"
    $passed++
}

Write-Output "$passed checks passed. Clinical rules, backend authorization/concurrency, and Android runtime behavior require implementation tests."
