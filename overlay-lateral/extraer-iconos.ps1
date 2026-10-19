# =============================================================================
#  Saca los iconos de mobs del TU PROPIO Minecraft
#
#  Las texturas estan dentro del .jar de la version que ya tienes instalada.
#  Este script las extrae, recorta la cara y la deja escalada en  iconos\
#
#  No descarga nada de internet.
# =============================================================================

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$Destino = Join-Path $PSScriptRoot 'iconos'
$Version = 'auto'          # o pon una fija: '26.2'
$Escala  = 8               # 8x -> iconos de 64x64 px

# -----------------------------------------------------------------------------
#  Tabla de recortes:  archivo dentro del jar  +  rectangulo de la cara
#
#  Para zombie/husk, creeper y enderman la cara frontal esta en (8,8) 8x8:
#  es el layout clasico de cabeza. Si alguno sale raro, cambia los numeros
#  aqui y vuelve a ejecutar; el contacto.png te deja ver como quedo cada uno.
# -----------------------------------------------------------------------------
$Iconos = @(
    @{ nombre='husk';     ruta='assets/minecraft/textures/entity/zombie/husk.png';      x=8;  y=8;  w=8;  h=8  }
    @{ nombre='spider';   ruta='assets/minecraft/textures/entity/spider/spider.png';    x=48; y=12; w=8;  h=8  }
    @{ nombre='creeper';  ruta='assets/minecraft/textures/entity/creeper/creeper.png';  x=8;  y=8;  w=8;  h=8  }
    @{ nombre='enderman'; ruta='assets/minecraft/textures/entity/enderman/enderman.png';x=8;  y=8;  w=8;  h=8  }
    @{ nombre='tnt';      ruta='assets/minecraft/textures/block/tnt_side.png';          x=0;  y=0;  w=16; h=16 }
    @{ nombre='diamante'; ruta='assets/minecraft/textures/item/diamond.png';            x=0;  y=0;  w=16; h=16 }
    @{ nombre='esmeralda';ruta='assets/minecraft/textures/item/emerald.png';            x=0;  y=0;  w=16; h=16 }
)

# ----------------------------------------------------------------- buscar el jar
$versionsDir = Join-Path $env:APPDATA '.minecraft\versions'
if (-not (Test-Path $versionsDir)) {
    Write-Host "No encuentro $versionsDir" -ForegroundColor Red
    Write-Host "Tienes Minecraft Java instalado en este PC?" -ForegroundColor DarkYellow
    pause; exit 1
}

if ($Version -eq 'auto') {
    $jar = Get-ChildItem $versionsDir -Recurse -Filter *.jar -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
} else {
    $jar = Get-Item (Join-Path $versionsDir "$Version\$Version.jar") -ErrorAction SilentlyContinue
}

if (-not $jar) { Write-Host 'No encontre ningun .jar de Minecraft.' -ForegroundColor Red; pause; exit 1 }
Write-Host "Usando: $($jar.FullName)" -ForegroundColor Cyan

# ----------------------------------------------------------------- extraer
New-Item -ItemType Directory -Force -Path $Destino | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)

$hechos = @()
try {
    foreach ($ico in $Iconos) {
        $entrada = $zip.Entries | Where-Object { $_.FullName -eq $ico.ruta } | Select-Object -First 1
        if (-not $entrada) {
            Write-Host "  [falta] $($ico.nombre)  ($($ico.ruta))" -ForegroundColor DarkYellow
            continue
        }

        $stream = $entrada.Open()
        $ms = New-Object System.IO.MemoryStream
        $stream.CopyTo($ms); $stream.Close()
        $ms.Position = 0
        $src = [System.Drawing.Image]::FromStream($ms)

        $lado = $ico.w * $Escala
        $out = New-Object System.Drawing.Bitmap($lado, $lado)
        $g = [System.Drawing.Graphics]::FromImage($out)
        # Vecino mas cercano: es pixel art, nada de suavizado.
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $g.PixelOffsetMode  = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $g.DrawImage($src,
            (New-Object System.Drawing.Rectangle(0, 0, $lado, $lado)),
            $ico.x, $ico.y, $ico.w, $ico.h,
            [System.Drawing.GraphicsUnit]::Pixel)
        $g.Dispose()

        $ruta = Join-Path $Destino "$($ico.nombre).png"
        $out.Save($ruta, [System.Drawing.Imaging.ImageFormat]::Png)
        $out.Dispose(); $src.Dispose(); $ms.Dispose()

        $hechos += $ico.nombre
        Write-Host "  [ok] $($ico.nombre).png" -ForegroundColor Green
    }
} finally {
    $zip.Dispose()
}

# ----------------------------------------------------------------- hoja de contacto
if ($hechos.Count -gt 0) {
    $lado = 64 * $Escala / 8
    $hoja = New-Object System.Drawing.Bitmap(($hechos.Count * ($lado + 8) + 8), ($lado + 30))
    $g = [System.Drawing.Graphics]::FromImage($hoja)
    $g.Clear([System.Drawing.Color]::FromArgb(30, 30, 40))
    $fuente = New-Object System.Drawing.Font('Consolas', 9)
    $i = 0
    foreach ($n in $hechos) {
        $img = [System.Drawing.Image]::FromFile((Join-Path $Destino "$n.png"))
        $g.DrawImage($img, (8 + $i * ($lado + 8)), 8, $lado, $lado)
        $g.DrawString($n, $fuente, [System.Drawing.Brushes]::White, (8 + $i * ($lado + 8)), ($lado + 12))
        $img.Dispose(); $i++
    }
    $g.Dispose()
    $hoja.Save((Join-Path $Destino 'contacto.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $hoja.Dispose()

    Write-Host ''
    Write-Host "Listo: $($hechos.Count) iconos en  $Destino" -ForegroundColor Green
    Write-Host 'Abre  iconos\contacto.png  para verlos todos de un vistazo.' -ForegroundColor Cyan
    Write-Host 'Si alguno salio recortado mal, ajusta x/y/w/h arriba y repite.' -ForegroundColor DarkGray
}

Write-Host ''
pause
