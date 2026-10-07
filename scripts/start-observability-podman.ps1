$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

& podman info --format '{{.Host.OS}}' *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Podman no responde. Ejecuta podman machine start.'
}

function Start-Container([string]$name, [string[]]$arguments) {
    & podman container exists $name
    if ($LASTEXITCODE -eq 0) {
        & podman start $name | Out-Null
    } else {
        & podman run -d --name $name @arguments | Out-Null
    }
    if ($LASTEXITCODE -ne 0) { throw "No se pudo iniciar $name" }
    Write-Host "$name iniciado"
}

& podman network exists bankcore-observability
if ($LASTEXITCODE -ne 0) {
    & podman network create bankcore-observability | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la red de observabilidad.' }
}

$prometheusConfig = Join-Path $repoRoot 'monitoring\prometheus.yml'
$otelConfig = Join-Path $repoRoot 'monitoring\otel-collector.yml'
$grafanaProvisioning = Join-Path $repoRoot 'monitoring\grafana\provisioning'
$grafanaDashboards = Join-Path $repoRoot 'monitoring\grafana\dashboards'

Start-Container 'bankcore-jaeger' @(
    '--network', 'bankcore-observability',
    '-p', '127.0.0.1:16686:16686', '-p', '127.0.0.1:4318:4318',
    '-e', 'COLLECTOR_OTLP_ENABLED=true',
    'docker.io/jaegertracing/all-in-one:1.76.0'
)
Start-Container 'bankcore-otel-collector' @(
    '--network', 'bankcore-observability',
    '-p', '127.0.0.1:4319:4318',
    '-v', "${otelConfig}:/etc/otelcol/config.yaml:ro",
    'docker.io/otel/opentelemetry-collector-contrib:0.137.0',
    '--config=/etc/otelcol/config.yaml'
)
Start-Container 'bankcore-prometheus' @(
    '--network', 'bankcore-observability',
    '-p', '127.0.0.1:9090:9090',
    '-v', "${prometheusConfig}:/etc/prometheus/prometheus.yml:ro",
    '-v', 'bankcore-prometheus-data:/prometheus',
    'docker.io/prom/prometheus:v3.6.0'
)
Start-Container 'bankcore-grafana' @(
    '--network', 'bankcore-observability',
    '-p', '127.0.0.1:3000:3000',
    '-e', 'GF_SECURITY_ADMIN_USER=admin',
    '-e', 'GF_SECURITY_ADMIN_PASSWORD=bankcore-local-only',
    '-v', 'bankcore-grafana-data:/var/lib/grafana',
    '-v', "${grafanaProvisioning}:/etc/grafana/provisioning:ro",
    '-v', "${grafanaDashboards}:/var/lib/grafana/dashboards:ro",
    'docker.io/grafana/grafana:12.2.0'
)

Write-Host 'Grafana: http://localhost:3000 (admin / bankcore-local-only)'
Write-Host 'Prometheus: http://localhost:9090'
Write-Host 'OTLP metrics: http://localhost:4319/v1/metrics'
Write-Host 'Jaeger: http://localhost:16686'
