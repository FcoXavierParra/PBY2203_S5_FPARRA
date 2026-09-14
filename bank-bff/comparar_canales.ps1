# ---------------------------------------------------------------------------
# Evidencia del patron BFF: la misma cuenta, pedida por los tres canales.
#
# Uso:
#   .\levantar.ps1            # deja los tres BFF corriendo
#   .\comparar_canales.ps1
#
# Por que este script es LA evidencia
# -----------------------------------
# El criterio "personaliza la informacion segun las necesidades de cada
# frontend" no se demuestra mostrando codigo: se demuestra mostrando que la
# misma cuenta, pedida por los tres canales, vuelve con tres formas y tres
# tamanos distintos. Eso es lo que hace este script, y por eso mide los bytes de
# cada respuesta.
#
# Cada canal se autentica con SU mecanismo, que tambien queda registrado:
#   web    -> HTTP Basic con usuario y rol
#   movil  -> token de dispositivo en cabecera propia
#   cajero -> clave de terminal + PIN, que devuelve una sesion de 2 minutos
#
# Deja la evidencia en ..\evidencias\
# ---------------------------------------------------------------------------

param(
    [long]$CuentaId = 0,
    [string]$UsuarioWeb = "ejecutivo",
    [string]$ClaveWeb = "ejecutivo123",
    [string]$TokenMovil = "token-movil-demo-2026",
    [string]$ClaveTerminal = "atm-key-demo-2026",
    [string]$Pin = "1234"
)

$ErrorActionPreference = "Stop"

# Saldo de la cuenta elegida. Se inicializa aqui para que el calculo del monto
# de retiro tambien funcione cuando se pasa -CuentaId a mano y no se recorre el
# listado que lo descubre.
$script:saldoInicial = 0
$salida = "..\evidencias"
New-Item -ItemType Directory -Force -Path $salida | Out-Null

# OJO: las variables de PowerShell NO distinguen mayusculas de minusculas. Estas
# tres constantes se llamaban $WEB, $MOVIL y $CAJERO, y guardar la respuesta de
# cada canal en $web y $movil sobrescribia la URL base: las llamadas siguientes
# armaban una URI con el objeto JSON pegado adelante. $CAJERO se salvo de casualidad
# porque su respuesta se guardo en $saldo. Los nombres de respuesta ahora son
# $respuestaWeb, $respuestaMovil y $saldo, sin colision posible.
$WEB = "https://localhost:8081"
$MOVIL = "https://localhost:8082"
$CAJERO = "https://localhost:8083"

# Los tres BFF sirven sobre TLS con certificados AUTOFIRMADOS, asi que ninguna
# autoridad los respalda y el cliente los rechazaria. Se acepta la cadena solo
# para esta ejecucion.
#
# Es aceptable aqui porque el cliente y el servidor corren en la misma maquina y
# el certificado lo genero este mismo proyecto hace un momento. En cualquier otro
# contexto desactivar la validacion anula el proposito de TLS: se cifraria el
# trafico sin saber contra quien.
#
# Invoke-RestMethod de Windows PowerShell 5.1 no tiene -SkipCertificateCheck
# -llego en PowerShell 6-, de ahi que haya que tocar ServicePointManager.
Add-Type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class AceptarCertificadoLocal : ICertificatePolicy {
    public bool CheckValidationResult(ServicePoint sp, X509Certificate cert, WebRequest req, int problem) {
        return true;
    }
}
"@
[System.Net.ServicePointManager]::CertificatePolicy = New-Object AceptarCertificadoLocal
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

# Motor real contra el que corrieron los BFF, leido del log de arranque y no
# declarado a mano: Spring Boot registra el perfil activo al iniciar. El nombre
# del archivo de evidencia lo lleva incorporado, de modo que la propia evidencia
# dice contra que motor corrio sin que nadie lo tenga que afirmar aparte.
#
# Los dos montajes son igualmente validos como evidencia, y lo son desde que la
# H2 dejo de ser una base POR BFF y paso a ser una sola compartida: mientras
# cada uno tenia la suya, la corrida H2 mostraba los tres canales sin ver los
# cambios de los otros, y eso exhibia el patron en su peor version por una
# limitacion del banco de pruebas y no del diseno.
$logArranque = Join-Path $env:TEMP "bff-web.log"
$motor = "h2"
if (Test-Path $logArranque) {
    if (Select-String -Path $logArranque -Pattern "profile is active: .oracle." -Quiet) { $motor = "oracle" }
}

$tiempos = @{}
$lineas = @()
function Registrar {
    param($Texto)
    # Write-Host y no Write-Output: Write-Output escribe al PIPELINE, y cuando
    # Registrar se llama desde otra funcion esas lineas se suman al valor de
    # retorno de esa funcion. Mostrar() devolvia asi un arreglo con todo el
    # texto en vez del numero de bytes, y la comparacion fallaba al multiplicar.
    Write-Host $Texto
    $script:lineas += $Texto
}

function Basic {
    param($Usuario, $Clave)
    $par = [System.Text.Encoding]::UTF8.GetBytes("${Usuario}:${Clave}")
    return "Basic " + [Convert]::ToBase64String($par)
}

# Mide cuanto tarda un endpoint. Se hace una llamada de calentamiento -la
# primera paga el handshake TLS y el JIT- y despues se toma la MEDIANA de
# varias: la mediana ignora el pico ocasional del sistema operativo, que en un
# promedio arrastraria el resultado.
function Medir {
    param($Uri, $Cabeceras, $Repeticiones = 7)
    Invoke-RestMethod -Uri $Uri -Headers $Cabeceras | Out-Null   # calentamiento
    $tiempos = @()
    for ($i = 0; $i -lt $Repeticiones; $i++) {
        $reloj = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-RestMethod -Uri $Uri -Headers $Cabeceras | Out-Null
        $reloj.Stop()
        $tiempos += $reloj.Elapsed.TotalMilliseconds
    }
    $ordenados = @($tiempos | Sort-Object)
    $medio = $ordenados.Count -shr 1
    return [Math]::Round($ordenados[$medio], 1)
}

function Mostrar {
    param($Titulo, $Json, $Uri, $Cabeceras)
    # El tamano se mide sobre el JSON compactado, no sobre el impreso con
    # sangria: la sangria es del visor, no viaja por la red.
    $compacto = ($Json | ConvertTo-Json -Depth 10 -Compress)
    $bytes = [System.Text.Encoding]::UTF8.GetByteCount($compacto)
    $campos = ($Json | Get-Member -MemberType NoteProperty).Count

    $ms = if ($Uri) { Medir $Uri $Cabeceras } else { $null }

    Registrar ""
    Registrar "--- $Titulo"
    Registrar ("    campos de primer nivel : {0}" -f $campos)
    Registrar ("    tamano de la respuesta : {0} bytes" -f $bytes)
    if ($ms) { Registrar ("    tiempo de respuesta    : {0} ms (mediana de 7)" -f $ms) }
    $script:tiempos[$Titulo] = $ms
    Registrar ""
    ($Json | ConvertTo-Json -Depth 10) -split "`n" | ForEach-Object { Registrar ("    " + $_.TrimEnd()) }
    return $bytes
}

Registrar "=============================================================================="
Registrar " PATRON BFF - LA MISMA CUENTA POR LOS TRES CANALES"
Registrar "=============================================================================="
Registrar (" Fecha: {0}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
Registrar (" Motor: {0}" -f $(if ($motor -eq "oracle") { "Oracle Autonomous Database, una sola base para los tres BFF" } else { "H2 en archivo, una sola base compartida por los tres BFF (montaje sin infraestructura)" }))

# --- 0. Elegir una cuenta que exista ---------------------------------------

$json = @{ "Content-Type" = "application/json" }

# Canal WEB: usuario y clave UNA vez, y de ahi en adelante el token.
$loginWeb = Invoke-RestMethod -Uri "$WEB/api/web/login" -Method Post -Headers $json `
            -Body (@{ usuario = $UsuarioWeb; clave = $ClaveWeb } | ConvertTo-Json -Compress)
$cabecerasWeb = @{ Authorization = "Bearer $($loginWeb.token)" }

if ($CuentaId -eq 0) {
    $cuentas = Invoke-RestMethod -Uri "$WEB/api/web/cuentas" -Headers $cabecerasWeb

    # Dos condiciones, y la segunda importa mas de lo que parece.
    #
    #   saldo >= 10.000   para que el retiro del cajero se pueda demostrar
    #                     AUTORIZADO y no rechazado por fondos insuficientes.
    #   cantidadMovimientos > 0  para que la ficha del canal web muestre los
    #                     agregados que calculo el batch de la Experiencia 1.
    #
    # Sin la segunda, ordenar solo por saldo caia en cuentas sin fila en
    # MOVIMIENTO_ANUAL: la evidencia salia con cantidadMovimientos y
    # totalDepositos en cero, y eso hacia parecer que el BFF no tiene de donde
    # sacarlos cuando el punto es justamente el contrario. Contra Oracle solo
    # 20 de las 50 cuentas traen esos agregados.
    #
    # Se ordena por cantidadMovimientos y no por saldo: entre las que sirven,
    # la mejor para evidenciar es la que mas calculo heredado tiene.
    $conAgregados = $cuentas |
        Where-Object { $null -ne $_.saldo -and $_.saldo -ge 10000 -and $_.cantidadMovimientos -gt 0 } |
        Sort-Object cantidadMovimientos -Descending

    # Contra H2 NINGUNA cuenta tiene agregados, porque alli no corrio ningun
    # batch. En ese caso se vuelve al criterio anterior y la evidencia lo
    # muestra en cero, que es la verdad de ese montaje.
    $elegida = if ($conAgregados) {
        $conAgregados | Select-Object -First 1
    } else {
        $cuentas | Where-Object { $null -ne $_.saldo } | Sort-Object saldo -Descending | Select-Object -First 1
    }

    $script:saldoInicial = [decimal]$elegida.saldo
    $CuentaId = $elegida.cuentaId
}
Registrar (" Cuenta de prueba: {0}" -f $CuentaId)

# --- 1. Canal WEB ----------------------------------------------------------

Registrar ""
Registrar "=============================================================================="
Registrar " 1. CANAL WEB  (:8081)   autenticacion: JWT tras login de '$UsuarioWeb'"
Registrar "=============================================================================="

$respuestaWeb = Invoke-RestMethod -Uri "$WEB/api/web/cuentas/$CuentaId" -Headers $cabecerasWeb
$bytesWeb = Mostrar "GET /api/web/cuentas/$CuentaId" $respuestaWeb "$WEB/api/web/cuentas/$CuentaId" $cabecerasWeb

# --- 2. Canal MOVIL --------------------------------------------------------

Registrar ""
Registrar "=============================================================================="
Registrar " 2. CANAL MOVIL  (:8082)   autenticacion: JWT tras registro del dispositivo"
Registrar "=============================================================================="

# Canal MOVIL: el token de dispositivo -credencial de larga vida- se cambia una
# vez por un token de sesion que caduca en 30 minutos.
$registroMovil = Invoke-RestMethod -Uri "$MOVIL/api/movil/registro" -Method Post `
                 -Headers ($json + @{ "X-Device-Token" = $TokenMovil }) `
                 -Body (@{ deviceId = "demo-android-01" } | ConvertTo-Json -Compress)
$cabecerasMovil = @{ Authorization = "Bearer $($registroMovil.token)" }
$respuestaMovil = Invoke-RestMethod -Uri "$MOVIL/api/movil/cuentas/$CuentaId/resumen" -Headers $cabecerasMovil
$bytesMovil = Mostrar "GET /api/movil/cuentas/$CuentaId/resumen" $respuestaMovil "$MOVIL/api/movil/cuentas/$CuentaId/resumen" $cabecerasMovil

# --- 3. Canal CAJERO -------------------------------------------------------

Registrar ""
Registrar "=============================================================================="
Registrar " 3. CANAL CAJERO  (:8083)   autenticacion: terminal + PIN, y JWT de 2 min"
Registrar "=============================================================================="

$cabecerasTerminal = $json + @{ "X-ATM-Terminal" = $ClaveTerminal }
$cuerpoSesion = @{ cuentaId = $CuentaId; pin = $Pin } | ConvertTo-Json -Compress

$sesion = Invoke-RestMethod -Uri "$CAJERO/api/cajero/sesion" -Method Post `
    -Headers $cabecerasTerminal -Body $cuerpoSesion
Registrar ""
Registrar ("    Sesion abierta, vence en {0} s" -f $sesion.expiraEnSegundos)

# El cajero exige AMBAS cosas en cada peticion: la clave del terminal, que
# identifica al equipo, y el token, que identifica al cliente.
$cabecerasSesion = @{
    "X-ATM-Terminal" = $ClaveTerminal
    "Authorization"  = "Bearer $($sesion.token)"
    "Content-Type"   = "application/json"
}
$saldo = Invoke-RestMethod -Uri "$CAJERO/api/cajero/saldo" -Headers $cabecerasSesion
$bytesCajero = Mostrar "GET /api/cajero/saldo" $saldo "$CAJERO/api/cajero/saldo" $cabecerasSesion

# --- 4. Comparacion --------------------------------------------------------

Registrar ""
Registrar "=============================================================================="
Registrar " COMPARACION"
Registrar "=============================================================================="
Registrar ""
Registrar ("  {0,-10} {1,9} {2,9} {3,9}   {4}" -f "canal", "bytes", "vs. web", "tiempo", "para que sirve")
Registrar ("  {0}" -f ("-" * 74))
Registrar ("  {0,-10} {1,9} {2,9} {3,8} ms   {4}" -f "web", $bytesWeb, "100 %", $tiempos["GET /api/web/cuentas/$CuentaId"], "ficha completa con agregados")
Registrar ("  {0,-10} {1,9} {2,8:N0}% {3,8} ms   {4}" -f "movil", $bytesMovil, (100.0 * $bytesMovil / $bytesWeb), $tiempos["GET /api/movil/cuentas/$CuentaId/resumen"], "saldo y ultimos movimientos")
Registrar ("  {0,-10} {1,9} {2,8:N0}% {3,8} ms   {4}" -f "cajero", $bytesCajero, (100.0 * $bytesCajero / $bytesWeb), $tiempos["GET /api/cajero/saldo"], "solo saldo disponible")
Registrar ""
Registrar "  Los tres leen la MISMA cuenta del mismo modulo de dominio. La diferencia"
Registrar "  esta enteramente en el BFF: que consulta, que agrega y que expone."

# --- 5. Lo que cada canal NO puede hacer -----------------------------------
#
# La personalizacion no es solo cuanto se entrega: tambien que operaciones
# existen. Se comprueba que los endpoints de un canal no estan en los otros.

Registrar ""
Registrar "=============================================================================="
Registrar " SUPERFICIE EXPUESTA POR CANAL"
Registrar "=============================================================================="
Registrar ""

function Probar {
    param($Descripcion, $Uri, $Cabeceras, $Metodo = "Get")
    try {
        Invoke-RestMethod -Uri $Uri -Headers $Cabeceras -Method $Metodo -ErrorAction Stop | Out-Null
        Registrar ("  {0,-52} responde" -f $Descripcion)
    } catch {
        $codigo = $_.Exception.Response.StatusCode.value__
        Registrar ("  {0,-52} {1}" -f $Descripcion, $(if ($codigo) { "HTTP $codigo" } else { "error [" + $Uri + "]: " + $_.Exception.Message }))
    }
}

Probar "web    -> estado anual (existe)"        "$WEB/api/web/cuentas/$CuentaId/estado-anual" $cabecerasWeb
Probar "movil  -> estado anual (no existe)"     "$MOVIL/api/web/cuentas/$CuentaId/estado-anual" $cabecerasMovil
Probar "cajero -> listado de cuentas (no existe)" "$CAJERO/api/web/cuentas" $cabecerasSesion
Probar "movil  -> sin token de dispositivo"     "$MOVIL/api/movil/cuentas/$CuentaId/resumen" @{}
Probar "cajero -> sin sesion, solo terminal"    "$CAJERO/api/cajero/saldo" @{ "X-ATM-Terminal" = $ClaveTerminal }

# --- 6. Inventario de APIs -------------------------------------------------
#
# El enunciado pide evidencia de "la ejecucion de CADA API". Las secciones
# anteriores muestran en detalle las tres respuestas que se comparan entre si;
# esta recorre todos los endpoints de los tres BFF y deja constancia de que cada
# uno se ejecuto, con su metodo, su autenticacion, el estado que devolvio y el
# tamano de su respuesta.

Registrar ""
Registrar "=============================================================================="
Registrar " INVENTARIO DE APIS - cada endpoint de los tres BFF"
Registrar "=============================================================================="
Registrar ""
Registrar ("  {0,-6} {1,-42} {2,-9} {3,8}  {4}" -f "canal", "endpoint", "estado", "bytes", "autenticacion")
Registrar ("  {0}" -f ("-" * 100))

function Invocar {
    param($Canal, $Metodo, $Uri, $Cabeceras, $Cuerpo, $Auth, $Etiqueta)

    $estado = "-"
    $bytes = 0
    try {
        $parametros = @{
            Uri         = $Uri
            Method      = $Metodo
            Headers     = $Cabeceras
            ErrorAction = "Stop"
        }
        if ($Cuerpo) { $parametros.Body = $Cuerpo }

        $r = Invoke-RestMethod @parametros
        $estado = "200 OK"
        if ($null -ne $r) {
            $bytes = [System.Text.Encoding]::UTF8.GetByteCount(($r | ConvertTo-Json -Depth 10 -Compress))
        }
    } catch {
        $codigo = $null
        if ($_.Exception.PSObject.Properties.Name -contains "Response" -and $_.Exception.Response) {
            $codigo = $_.Exception.Response.StatusCode.value__
        }
        $estado = if ($codigo) { "HTTP $codigo" } else { "sin respuesta" }
    }

    Registrar ("  {0,-6} {1,-42} {2,-9} {3,8}  {4}" -f $Canal, $Etiqueta, $estado, $bytes, $Auth)
}


Invocar "web"    "Get"  "$WEB/api/web/cuentas"                              $cabecerasWeb   $null "JWT web (rol EJECUTIVO)" "GET  /api/web/cuentas"
Invocar "web"    "Get"  "$WEB/api/web/cuentas/$CuentaId"                    $cabecerasWeb   $null "JWT web"               "GET  /api/web/cuentas/{id}"
Invocar "web"    "Get"  "$WEB/api/web/cuentas/$CuentaId/estado-anual"       $cabecerasWeb   $null "JWT web"               "GET  /api/web/cuentas/{id}/estado-anual"
Invocar "web"    "Get"  "$WEB/api/web/transacciones?pagina=0&tamano=10"     $cabecerasWeb   $null "JWT web"               "GET  /api/web/transacciones"
Invocar "movil"  "Get"  "$MOVIL/api/movil/cuentas/$CuentaId/resumen"        $cabecerasMovil $null "JWT movil"             "GET  /api/movil/cuentas/{id}/resumen"
Invocar "movil"  "Get"  "$MOVIL/api/movil/cuentas/$CuentaId/movimientos"    $cabecerasMovil $null "JWT movil"             "GET  /api/movil/cuentas/{id}/movimientos"
# Sesion nueva para el cajero, creada JUSTO ANTES de usarla.
#
# Estaba mas arriba y expiraba: el token de este canal dura dos minutos y entre
# su emision y el retiro corren las 24 llamadas de la medicion de tiempos. El
# retiro devolvia 403 y parecia un defecto, cuando era la caducidad funcionando
# exactamente como se diseno.
$sesionInv = Invoke-RestMethod -Uri "$CAJERO/api/cajero/sesion" -Method Post `
    -Headers $cabecerasTerminal -Body (@{ cuentaId = $CuentaId; pin = $Pin } | ConvertTo-Json -Compress)
$cabecerasInv = @{
    "X-ATM-Terminal" = $ClaveTerminal
    "Authorization"  = "Bearer $($sesionInv.token)"
    "Content-Type"   = "application/json"
}

Invocar "cajero" "Post" "$CAJERO/api/cajero/sesion"                         $cabecerasTerminal (@{ cuentaId = $CuentaId; pin = $Pin } | ConvertTo-Json -Compress) "terminal + PIN -> JWT" "POST /api/cajero/sesion"
Invocar "cajero" "Get"  "$CAJERO/api/cajero/saldo"                          $cabecerasInv   $null "terminal + JWT cajero"     "GET  /api/cajero/saldo"
# El inventario prueba el retiro con un monto NO dispensable a proposito.
#
# Su objetivo es acreditar que el endpoint existe y es alcanzable con las
# credenciales correctas, no repetir la demostracion funcional que viene mas
# abajo. Con un monto valido consumiria los fondos de la cuenta y dejaria sin
# caso exitoso a esa demostracion, que es donde el rechazo y la autorizacion se
# ven juntos. Un 400 aqui es la respuesta esperada: la peticion llego, se
# autentico y la regla del canal la evaluo.
Invocar "cajero" "Post" "$CAJERO/api/cajero/retiro"                         $cabecerasInv   (@{ monto = 7500 } | ConvertTo-Json -Compress) "terminal + JWT cajero" "POST /api/cajero/retiro"

Registrar ""
Registrar "  Nueve endpoints en total: cuatro del canal web, dos del movil y tres del"
Registrar "  cajero. La cantidad no es casual y es en si misma parte de la evidencia:"
Registrar "  el canal con mas superficie es el que corre en un equipo de confianza, y el"
Registrar "  que entrega dinero en la calle expone lo minimo para operar."
Registrar ""
Registrar "  El 400 del retiro es la respuesta ESPERADA y no un fallo: este inventario lo"
Registrar "  prueba con un monto no dispensable a proposito. Su objetivo es acreditar que"
Registrar "  el endpoint existe y autentica, no repetir la demostracion funcional que"
Registrar "  viene a continuacion; con un monto valido consumiria el saldo de la cuenta y"
Registrar "  dejaria sin caso exitoso a esa demostracion, que es donde el rechazo y la"
Registrar "  autorizacion se ven juntos."

# --- 7. Operacion critica del cajero ---------------------------------------

Registrar ""
Registrar "=============================================================================="
Registrar " OPERACION CRITICA: RETIRO (solo el canal cajero)"
Registrar "=============================================================================="
Registrar ""

# El monto valido se calcula desde el saldo ACTUAL, no del que tenia la cuenta
# al empezar el script: el inventario de la seccion anterior ya ejecuto un
# retiro real sobre ella. Usar el saldo inicial pediria un monto que la cuenta
# ya no tiene, y el caso "valido" saldria rechazado por fondos insuficientes.
#
# Que haya que releerlo es en si mismo evidencia de algo: el debito que hizo el
# inventario quedo persistido y los canales lo ven.
$saldoActual = [decimal](Invoke-RestMethod -Uri "$CAJERO/api/cajero/saldo" -Headers $cabecerasSesion).saldoDisponible
# Sesion nueva, abierta JUSTO antes del retiro.
#
# El token del cajero dura dos minutos y entre su emision y este punto corren
# las 24 llamadas de la medicion de tiempos, asi que reutilizar el anterior seria
# fragil aunque hoy alcance. Abrir una sesion por operacion es ademas lo que hace
# un cajero real: una por cliente atendido.
#
# Nota: los 403 que se veian aqui NO eran caducidad. El metodo de retiro exigia
# una cabecera X-ATM-Session que dejo de existir al migrar a Bearer, y la
# peticion terminaba despachada al manejador de errores, que la cadena de
# seguridad deniega. Se corrigio en CajeroController tomando el token del
# contexto de autenticacion.
$sesionRetiro = Invoke-RestMethod -Uri "$CAJERO/api/cajero/sesion" -Method Post `
    -Headers $cabecerasTerminal -Body (@{ cuentaId = $CuentaId; pin = $Pin } | ConvertTo-Json -Compress)
$cabecerasSesion = @{
    "X-ATM-Terminal" = $ClaveTerminal
    "Authorization"  = "Bearer $($sesionRetiro.token)"
    "Content-Type"   = "application/json"
}

$montoValido = [Math]::Min(200000, [Math]::Floor([decimal]::Divide($saldoActual, 10000)) * 10000)
$hayFondos = $montoValido -ge 10000
if (-not $hayFondos) { $montoValido = 10000 }

foreach ($caso in @(
    @{ etiqueta = "monto no dispensable (7.500)"; monto = 7500 },
    @{ etiqueta = "sobre el tope de la operacion (300.000)"; monto = 300000 },
    @{ etiqueta = ("monto valido ({0:N0})" -f $montoValido); monto = $montoValido; requiereFondos = $true }
)) {
    # El caso valido se omite si el inventario ya consumio los fondos. Se dice
    # con todas sus letras en vez de dejar un 409 que se leeria como un fallo.
    if ($caso.requiereFondos -and -not $hayFondos) {
        Registrar ("  {0,-42} {1}" -f $caso.etiqueta,
            ("omitido: el retiro del inventario dejo la cuenta en {0:N0} y no alcanza para otro" -f $saldoActual))
        continue
    }
    $cuerpo = @{ monto = $caso.monto } | ConvertTo-Json -Compress
    try {
        $r = Invoke-RestMethod -Uri "$CAJERO/api/cajero/retiro" -Method Post `
             -Headers $cabecerasSesion -Body $cuerpo -ErrorAction Stop
        if ($caso.requiereFondos) { $script:montoRetirado = $caso.monto; $script:saldoTrasRetiro = [decimal]$r.saldoRestante }
        Registrar ("  {0,-42} {1,-22} saldo restante: {2}" -f $caso.etiqueta, $r.codigo, $r.saldoRestante)
    } catch {
        $detalle = $null
        try { $detalle = ($_.ErrorDetails.Message | ConvertFrom-Json).codigo } catch {}
        Registrar ("  {0,-42} {1}" -f $caso.etiqueta, $(if ($detalle) { $detalle } else { "HTTP " + $_.Exception.Response.StatusCode.value__ }))
    }
}

Registrar ""
Registrar "  Las dos primeras reglas -multiplo de 10.000 y tope por operacion- son del"
Registrar "  CANAL y viven en el BFF del cajero. La validacion de saldo suficiente es del"
Registrar "  BANCO y vive en el modulo comun: cualquier canal futuro la hereda, mientras"
Registrar "  que las del cajero no se le imponen a nadie mas."

# --- 8. Coherencia entre canales -------------------------------------------
#
# La prueba de que los tres BFF son capas sobre UN MISMO backend y no tres
# aplicaciones que casualmente muestran datos parecidos.
#
# El cajero acaba de debitar la cuenta. Si web y movil informan el saldo nuevo
# -sin que nadie los haya notificado- es porque los tres leen la misma fila.
# Esta seccion no existia mientras cada BFF tenia su propia H2 en memoria: alli
# habria mostrado tres saldos distintos, que es lo contrario de lo que hay que
# demostrar.

Registrar ""
Registrar "=============================================================================="
Registrar " COHERENCIA ENTRE CANALES: UN RETIRO, TRES VISTAS"
Registrar "=============================================================================="
Registrar ""

if ($null -eq $script:montoRetirado) {

    Registrar "  No hubo retiro autorizado en esta corrida, asi que no hay cambio que"
    Registrar "  propagar. La comparacion se omite en vez de mostrar tres saldos iguales"
    Registrar "  que no probarian nada."

} else {

    # Sesion nueva: la anterior la revoco el propio retiro al entregar el dinero.
    # Que haya que abrirla otra vez es, de paso, la revocacion funcionando.
    $sesionLectura = Invoke-RestMethod -Uri "$CAJERO/api/cajero/sesion" -Method Post `
        -Headers $cabecerasTerminal -Body (@{ cuentaId = $CuentaId; pin = $Pin } | ConvertTo-Json -Compress)
    $cabecerasLectura = @{
        "X-ATM-Terminal" = $ClaveTerminal
        "Authorization"  = "Bearer $($sesionLectura.token)"
    }

    $saldoWeb    = [decimal](Invoke-RestMethod -Uri "$WEB/api/web/cuentas/$CuentaId" -Headers $cabecerasWeb).saldo
    $saldoMovil  = [decimal](Invoke-RestMethod -Uri "$MOVIL/api/movil/cuentas/$CuentaId/resumen" -Headers $cabecerasMovil).saldo
    $saldoCajero = [decimal](Invoke-RestMethod -Uri "$CAJERO/api/cajero/saldo" -Headers $cabecerasLectura).saldoDisponible

    Registrar ("  Retiro efectuado por el cajero: {0:N0}" -f $script:montoRetirado)
    Registrar ("  Saldo antes / despues        : {0:N0}  ->  {1:N0}" -f $saldoActual, $script:saldoTrasRetiro)
    Registrar ""
    Registrar ("  {0,-8} {1,-46} {2,12}" -f "canal", "endpoint consultado DESPUES del retiro", "saldo")
    Registrar ("  " + ("-" * 68))
    Registrar ("  {0,-8} {1,-46} {2,12:N0}" -f "web",    "GET /api/web/cuentas/$CuentaId",           $saldoWeb)
    Registrar ("  {0,-8} {1,-46} {2,12:N0}" -f "movil",  "GET /api/movil/cuentas/$CuentaId/resumen", $saldoMovil)
    Registrar ("  {0,-8} {1,-46} {2,12:N0}" -f "cajero", "GET /api/cajero/saldo",                    $saldoCajero)
    Registrar ""

    $coinciden = ($saldoWeb -eq $saldoMovil) -and ($saldoMovil -eq $saldoCajero)

    if ($coinciden) {
        Registrar "  Los tres coinciden. Ninguno de los BFF sabe de la existencia de los otros"
        Registrar "  dos: lo que comparten es el modulo de dominio y la base, y cada uno decide"
        Registrar "  por su cuenta cuanto de eso expone y con que forma. Eso es el patron."
    } else {
        Registrar "  ATENCION: los saldos NO coinciden. Con la base compartida no deberia"
        Registrar "  ocurrir; revisar que los tres BFF esten sobre el mismo motor."
    }

    Registrar ""
    # Las cifras salen de lo MEDIDO en esta misma corrida, no escritas a mano.
    $camposWeb   = ($respuestaWeb   | Get-Member -MemberType NoteProperty).Count
    $camposMovil = ($respuestaMovil | Get-Member -MemberType NoteProperty).Count
    # Estaban fijas y decian 43 bytes; contra Oracle la respuesta del cajero
    # mide 40, asi que el cierre contradecia a la tabla que tiene tres parrafos
    # mas arriba en el mismo archivo.
    Registrar "  Notese que cada canal lo informa a SU manera y no todos exponen lo mismo:"
    Registrar ("  el web lo entrega dentro de una ficha de {0} campos con agregados anuales," -f $camposWeb)
    Registrar ("  el movil dentro de un resumen de {0}, y el cajero solo, en {1} bytes. El dato" -f $camposMovil, $bytesCajero)
    Registrar "  es uno; la representacion es del canal."
}

# --- Guardar ---------------------------------------------------------------

$destino = Join-Path $salida "01_comparacion_canales_$motor.txt"
Set-Content -Path $destino -Value $lineas -Encoding utf8

Write-Output ""
Write-Output "Evidencia guardada en: $((Resolve-Path $destino).Path)"
