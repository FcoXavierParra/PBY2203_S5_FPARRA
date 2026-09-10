# ---------------------------------------------------------------------------
# Levanta los tres BFF y espera a que respondan.
#
# Uso:
#   .\levantar.ps1                  # H2 en memoria, sin credenciales
#   .\levantar.ps1 -Perfil oracle   # contra la base de la Experiencia 1
#   .\levantar.ps1 -Detener         # los baja
#
# Cada BFF es una aplicacion Spring Boot independiente, en su propio puerto y su
# propio proceso. Que haya que lanzar tres procesos no es un inconveniente del
# montaje: es la consecuencia visible de la estrategia elegida. Si se pudieran
# levantar todos con un solo comando compartiendo JVM, no serian backends
# separados.
#
#   bff-web     https://localhost:8081   navegador de escritorio
#   bff-movil   https://localhost:8082   aplicacion de telefono
#   bff-cajero  https://localhost:8083   terminal de autoservicio
# ---------------------------------------------------------------------------

param(
    [switch]$Detener,

    # Perfil de Spring con el que arrancan los tres.
    #   (vacio)  H2 en memoria, poblada con los CSV oficiales. Sin credenciales.
    #   oracle   la Autonomous Database que poblo la Experiencia 1. Exige
    #            $env:ORACLE_PASSWORD y deja a los tres BFF sobre la MISMA base,
    #            con lo que un retiro del cajero si se ve desde web y movil.
    [string]$Perfil = ""
)

$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
}
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
if (-not (Test-Path $java)) {
    Write-Output "ERROR: no se encontro java en $java. Definir JAVA_HOME."
    exit 1
}

if ($Perfil -eq "oracle") {

    # Las tres variables que consume application-oracle.properties. Solo la
    # contrasena se exige: las otras dos tienen valor por defecto, igual que en
    # correr_oracle.ps1 de la Experiencia 1.
    #
    # Faltaban aqui, y el sintoma no se parecia en nada a la causa: sin URL,
    # spring.datasource.url quedaba vacio y Hibernate moria con "Unable to
    # determine Dialect without JDBC metadata", que no menciona ni Oracle ni
    # variables de entorno por ninguna parte.
    #
    # Servicio _low: es el grupo de consumo con mayor concurrencia de sentencias.
    if (-not $env:ORACLE_JDBC_URL) {
        $env:ORACLE_JDBC_URL = "jdbc:oracle:thin:@oraclecloudfparradb_low?TNS_ADMIN=C:/oracle/wallet"
    }
    if (-not $env:ORACLE_USER) {
        $env:ORACLE_USER = "ADMIN"
    }
    if (-not $env:ORACLE_PASSWORD) {
        Write-Output "ERROR: el perfil oracle necesita la contrasena. Definirla con COMILLAS SIMPLES:"
        Write-Output "  `$env:ORACLE_PASSWORD = 'la-clave'"
        Write-Output "  (con comillas dobles PowerShell expande las `$variables que haya dentro)"
        exit 1
    }

    Write-Output "URL     : $env:ORACLE_JDBC_URL"
    Write-Output "Usuario : $env:ORACLE_USER"
    Write-Output ""

    # --- Sonda: UNA conexion antes de lanzar nada --------------------------
    #
    # Levantar los tres BFF con credenciales equivocadas cuesta NUEVE intentos
    # de login -tres aplicaciones, cada una reintentando tres veces al
    # inicializar su pool- y Autonomous Database bloquea la cuenta ADMIN a los
    # diez consecutivos. Con una sola corrida a ciegas se llega al limite.
    #
    # Esta comprobacion gasta un intento, dice exactamente que pasa y aborta.
    # Es la misma disciplina que ya tenia correr_oracle.ps1 en la Experiencia 1
    # y que no se habia portado aqui.
    $m2 = Join-Path $env:USERPROFILE ".m2\repository"
    $jarsOracle = @(
        (Get-ChildItem (Join-Path $m2 "com\oracle\database\jdbc\ojdbc11") -Filter "ojdbc11-*.jar" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch "sources|javadoc" } | Select-Object -First 1),
        (Get-ChildItem (Join-Path $m2 "com\oracle\database\security\oraclepki") -Filter "oraclepki-*.jar" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch "sources|javadoc" } | Select-Object -First 1)
    ) | Where-Object { $_ }

    if ($jarsOracle.Count -lt 2) {
        Write-Output "ERROR: faltan ojdbc11 u oraclepki en $m2."
        Write-Output "       Ejecutar antes:  mvn install -DskipTests"
        exit 1
    }

    Write-Output "Probando la conexion (un solo intento)..."
    & $java -cp (($jarsOracle | ForEach-Object { $_.FullName }) -join ";") "$PSScriptRoot\herramientas\ProbarConexion.java"
    if ($LASTEXITCODE -ne 0) {
        Write-Output ""
        Write-Output "No se lanzan los BFF. Se gasto UN intento de login, no nueve."
        exit 1
    }
    Write-Output ""
}

# Puerto y tope de memoria de cada canal.
#
# Los heap NO son iguales, y la diferencia es una decision de diseno, no un
# ajuste para que quepa: cada BFF se dimensiona segun lo que realmente maneja.
#
#   web     512 MB  arma respuestas de ~1 KB con agregados anuales y pagina
#                   listados de cientos de filas; es el que mas objetos vivos
#                   sostiene a la vez.
#   movil   256 MB  devuelve 340 bytes por peticion y nunca lista mas de cinco
#                   movimientos. Reservarle lo mismo que al web seria memoria
#                   que el sistema operativo no puede dar a nadie mas.
#   cajero  192 MB  responde 43 bytes y tiene tres endpoints. Es el proceso mas
#                   chico porque es el que menos hace.
#
# Que el patron BFF permita esto es parte de su valor: con un backend unico
# habria que dimensionar para el peor caso de todos los clientes.
$modulos = [ordered]@{
    "web"    = @{ puerto = 8081; heap = "512m" }
    "movil"  = @{ puerto = 8082; heap = "256m" }
    "cajero" = @{ puerto = 8083; heap = "192m" }
}

function Detener-Bff {
    Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $java } |
        Stop-Process -Force
}

# --- Detener ---------------------------------------------------------------

if ($Detener) {
    Detener-Bff
    Write-Output "BFF detenidos."
    exit 0
}

# --- Arrancar --------------------------------------------------------------

$base = $PSScriptRoot
$faltantes = @()
foreach ($m in $modulos.Keys) {
    $jar = Join-Path $base "bff-$m\target\bff-$m-0.0.1-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) { $faltantes += "bff-$m" }
}
if ($faltantes.Count -gt 0) {
    Write-Output ("ERROR: faltan los jar de: {0}" -f ($faltantes -join ", "))
    Write-Output "       Ejecutar antes:  mvn install -DskipTests"
    exit 1
}

# Certificados TLS. Se generan si faltan, para que clonar el repositorio y
# ejecutar siga siendo un solo comando: los keystore no se versionan porque
# contienen la clave privada del servidor.
$certs = Join-Path $base "certs"
if (-not (Test-Path (Join-Path $certs "bff-web.p12"))) {
    Write-Output "Faltan los certificados TLS. Generandolos..."
    & (Join-Path $base "generar_certificados.ps1")
    if ($LASTEXITCODE -ne 0) {
        Write-Output "ERROR: no se pudieron generar los certificados."
        exit 1
    }
    Write-Output ""
}

# Se baja cualquier BFF que siguiera corriendo, ANTES de lanzar los nuevos.
#
# Sin esto la comprobacion de arranque es una mentira: si una instancia previa
# sigue ocupando los puertos, los procesos nuevos mueren con "port already in
# use" -o peor, mueren por otra causa- y el chequeo de puerto responde que si
# porque le contesta la instancia vieja. Paso exactamente eso: una corrida con
# -Perfil oracle fallo con ORA-01017, este script informo que los tres
# respondian, y la evidencia se genero contra las instancias H2 anteriores.
$previos = @(Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $java })
if ($previos.Count -gt 0) {
    Write-Output ("Bajando {0} instancia(s) previa(s)..." -f $previos.Count)
    Detener-Bff
    Start-Sleep -Seconds 3
}

if ($Perfil) {
    Write-Output "Perfil: $Perfil"
} else {
    Write-Output "Perfil: (por defecto) H2 en memoria, una base por BFF"
}
Write-Output ""

$procesos = @{}
$fallidos = @{}
$pendientes = @()

# Se arrancan DE A UNO, esperando a que cada cual responda antes de lanzar el
# siguiente.
#
# En paralelo los tres se estorban: cada aplicacion carga las 3000 filas del
# dataset y levanta su contexto de Spring, y en un equipo con la memoria justa
# eso paginaba tanto que ninguno alcanzaba a responder dentro del plazo. Medido:
# uno solo tarda 113 segundos; los tres a la vez no terminaban ni en 300.
#
# Secuencial el total es mayor -unos seis minutos- pero es predecible, y sobre
# todo no deja el montaje a medias, que es lo que hace que la evidencia se
# genere contra el motor equivocado.
foreach ($m in $modulos.Keys) {

    $jar = Join-Path $base "bff-$m\target\bff-$m-0.0.1-SNAPSHOT.jar"
    $heap = "-Xmx$($modulos[$m].heap) -XX:+UseSerialGC"
    $argumentos = if ($Perfil) { "$heap -jar `"$jar`" --spring.profiles.active=$Perfil" } else { "$heap -jar `"$jar`"" }

    # -WorkingDirectory es necesario: la ruta del keystore en las propiedades es
    # relativa (file:./certs/...), asi que el proceso tiene que arrancar desde
    # bank-bff y no desde donde este parado quien ejecuta el script.
    $procesos[$m] = Start-Process -FilePath $java -ArgumentList $argumentos -WindowStyle Hidden -PassThru `
        -WorkingDirectory $base `
        -RedirectStandardOutput "$env:TEMP\bff-$m.log" `
        -RedirectStandardError "$env:TEMP\bff-$m.err"

    Write-Output ("  bff-{0,-7} lanzado en :{1} con heap {2} (PID {3})" -f $m, $modulos[$m].puerto, $modulos[$m].heap, $procesos[$m].Id)

    $limite = (Get-Date).AddSeconds(300)
    $listo = $false

    while ((Get-Date) -lt $limite) {
        Start-Sleep -Seconds 4

        # 1. El proceso que ESTE script lanzo, muerto: es fallo, sin importar
        #    que el puerto conteste. Ahi esta la trampa que esto corrige: una
        #    instancia previa ocupando el puerto haria pasar por sano un
        #    arranque que fracaso.
        if ($procesos[$m].HasExited) {
            $codigoSalida = $procesos[$m].ExitCode
            $fallidos[$m] = if ($null -ne $codigoSalida) { "el proceso termino con codigo $codigoSalida" } else { "el proceso termino durante el arranque" }
            break
        }

        # 2. Fallo de arranque anunciado en el log, aunque el proceso agonice.
        $log = "$env:TEMP\bff-$m.log"
        if (Test-Path $log) {
            # No se llama $error: es una variable automatica de PowerShell y de
            # solo lectura, asi que asignarla aborta el script.
            $fallaLog = Select-String -Path $log -Pattern "APPLICATION FAILED TO START|ORA-\d+|Web server failed to start|Unable to determine Dialect" |
                        Select-Object -First 1
            if ($fallaLog) {
                $fallidos[$m] = ($fallaLog.Line -replace '.*(ORA-\d+[^\r\n]*)', '$1').Trim()
                break
            }
        }

        # 3. Recien ahora vale preguntarle al puerto.
        if (Test-NetConnection -ComputerName localhost -Port $modulos[$m].puerto -InformationLevel Quiet -WarningAction SilentlyContinue) {
            $listo = $true
            break
        }
    }

    if ($listo) {
        Write-Output ("  bff-{0,-7} responde en https://localhost:{1}" -f $m, $modulos[$m].puerto)
    } elseif (-not $fallidos.ContainsKey($m)) {
        $pendientes += $m
    }

    # Si uno fallo, no tiene sentido seguir levantando los demas.
    if ($fallidos.ContainsKey($m)) { break }
}


if ($fallidos.Count -gt 0 -or $pendientes.Count -gt 0) {
    Write-Output ""
    foreach ($m in $fallidos.Keys) {
        Write-Output ("  bff-{0,-7} FALLO: {1}" -f $m, $fallidos[$m])
    }
    foreach ($m in $pendientes) {
        Write-Output ("  bff-{0,-7} no respondio dentro del plazo" -f $m)
    }
    Write-Output ""
    if (($fallidos.Values -join " ") -match "Unable to determine Dialect") {
        Write-Output "  Hibernate no pudo determinar el dialecto: la URL de la base llego vacia."
        Write-Output "  Revisar `$env:ORACLE_JDBC_URL, que este script deberia haber definido."
    } elseif (($fallidos.Values -join " ") -match "ORA-01017") {
        Write-Output "  ORA-01017 es usuario o contrasena. Definirla con COMILLAS SIMPLES:"
        Write-Output "    `$env:ORACLE_PASSWORD = 'la-clave'"
        Write-Output "  Con dobles, PowerShell expande las `$variables y backticks que haya dentro."
    } elseif (($fallidos.Values -join " ") -match "ORA-12514") {
        Write-Output "  ORA-12514 es la base detenida. Iniciarla en la consola de Oracle Cloud."
    }
    Write-Output "  Log completo en $env:TEMP\bff-<modulo>.log"
    Write-Output ""
    Write-Output "  Se bajan los procesos para no dejar un montaje a medias, que es lo que"
    Write-Output "  haria que la evidencia siguiente se generara contra el motor equivocado."
    Detener-Bff
    exit 1
}

Write-Output ""
foreach ($m in $modulos.Keys) {
    $cargadas = Select-String -Path "$env:TEMP\bff-$m.log" -Pattern "Carga lista|ya tiene datos" |
                Select-Object -First 1
    if ($cargadas) {
        Write-Output ("  bff-{0,-7} {1}" -f $m, ($cargadas.Line -split ' : ', 2)[-1])
    }
}

Write-Output ""
Write-Output "Los tres BFF estan arriba. Paso siguiente:  .\comparar_canales.ps1"
