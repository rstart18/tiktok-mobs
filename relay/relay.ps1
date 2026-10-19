# =============================================================================
#  TikTokMobs - Relay
#
#  Lee TU TikFinity en 127.0.0.1:21213 y empuja los eventos al servidor de
#  Minecraft de tu amigo. Solo hace conexiones de SALIDA: no abres ningun
#  puerto, no montas tuneles y funciona detras de cualquier router.
#
#  No instala nada: usa el PowerShell que ya trae Windows.
# =============================================================================

$ErrorActionPreference = 'Stop'
$ConfigPath = Join-Path $PSScriptRoot 'relay-config.txt'

function Read-Config {
    if (Test-Path $ConfigPath) {
        $cfg = @{}
        foreach ($line in Get-Content $ConfigPath) {
            if ($line -match '^\s*([^=#]+?)\s*=\s*(.*)$') { $cfg[$Matches[1]] = $Matches[2].Trim() }
        }
        if ($cfg.servidor -and $cfg.id) { return $cfg }
    }

    Write-Host ''
    Write-Host '  Primera vez: necesito tres datos.' -ForegroundColor Cyan
    Write-Host '  (se guardan en relay-config.txt y no vuelvo a preguntar)' -ForegroundColor DarkGray
    Write-Host ''

    $servidor = Read-Host '  Direccion del servidor (ej: http://algo.joinmc.link:8787)'
    $id       = Read-Host '  Tu id de streamer (el que te pase tu amigo, ej: pana)'
    $token    = Read-Host '  Token (deja vacio si no te dieron uno)'

    @"
# Datos del relay de TikTokMobs. Borra este archivo para volver a configurarlo.
servidor = $servidor
id = $id
token = $token
"@ | Set-Content $ConfigPath -Encoding UTF8

    return @{ servidor = $servidor; id = $id; token = $token }
}

$cfg = Read-Config
$destino = $cfg.servidor.TrimEnd('/') + '/ingest?source=' + [Uri]::EscapeDataString($cfg.id)
if ($cfg.token) { $destino += '&token=' + [Uri]::EscapeDataString($cfg.token) }

Write-Host ''
Write-Host '  ========================================================' -ForegroundColor DarkGray
Write-Host '   TikTokMobs Relay' -ForegroundColor Yellow
Write-Host '   TikFinity (tu PC)  ->  servidor de Minecraft' -ForegroundColor DarkGray
Write-Host '  ========================================================' -ForegroundColor DarkGray
Write-Host "   streamer : $($cfg.id)"
Write-Host "   destino  : $($cfg.servidor)"
Write-Host ''
Write-Host '   Deja esta ventana abierta mientras transmites.' -ForegroundColor Cyan
Write-Host '   Ctrl+C para parar.' -ForegroundColor DarkGray
Write-Host ''

$enviados = 0
$intento  = 0

while ($true) {
    $ws = $null
    try {
        $ws = New-Object System.Net.WebSockets.ClientWebSocket
        $ct = [System.Threading.CancellationToken]::None

        Write-Host '   conectando a TikFinity...' -ForegroundColor DarkGray
        $null = $ws.ConnectAsync([Uri]'ws://127.0.0.1:21213/', $ct).GetAwaiter().GetResult()

        $intento = 0
        Write-Host '   [OK] conectado a TikFinity' -ForegroundColor Green
        Write-Host ''

        $buffer = New-Object byte[] 8192
        $seg = New-Object System.ArraySegment[byte] -ArgumentList @(, $buffer)

        while ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
            # Un mensaje puede llegar partido en varios frames: hay que juntarlos
            # antes de reenviar o el JSON llega cortado.
            $sb = New-Object System.Text.StringBuilder
            do {
                $r = $ws.ReceiveAsync($seg, $ct).GetAwaiter().GetResult()
                if ($r.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { break }
                $null = $sb.Append([System.Text.Encoding]::UTF8.GetString($buffer, 0, $r.Count))
            } while (-not $r.EndOfMessage)

            $msg = $sb.ToString()
            if (-not $msg) { continue }

            try {
                Invoke-RestMethod -Method Post -Uri $destino -Body ([System.Text.Encoding]::UTF8.GetBytes($msg)) `
                                  -ContentType 'application/json' -TimeoutSec 8 | Out-Null
                $enviados++
                if ($enviados % 10 -eq 1) {
                    Write-Host "   enviados: $enviados" -ForegroundColor DarkGray
                }
            } catch {
                # Que el servidor este caido no debe tumbar el relay: seguimos leyendo.
                Write-Host "   [!] no pude enviar al servidor: $($_.Exception.Message)" -ForegroundColor Red
            }
        }
    } catch {
        Write-Host "   [!] $($_.Exception.Message)" -ForegroundColor Red
        Write-Host '       Esta abierta la app de TikFinity?' -ForegroundColor DarkYellow
    } finally {
        if ($ws) { try { $ws.Dispose() } catch { } }
    }

    $intento++
    $espera = [Math]::Min(30, [Math]::Pow(2, [Math]::Min($intento, 5)))
    Write-Host "   reintento en $espera s..." -ForegroundColor DarkGray
    Start-Sleep -Seconds $espera
}
