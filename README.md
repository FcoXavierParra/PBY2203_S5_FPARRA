# bank-bff — PBY2203 Desarrollo Backend III · Experiencia 2, Semana 5

**Implementando el patrón arquitectónico Backend for Frontend (BFF)**

Tres backends independientes sobre los datos del Banco XYZ, uno por tipo de cliente —web,
móvil y cajero automático—, cada uno con sus propios endpoints, sus propios DTO, **su propio
certificado TLS y su propia clave de firma de tokens**.

Actividad sumativa individual. Continuidad de la formativa de la Semana 4.

---

## Índice de la entrega

Los tres aspectos que pide la sección *Entrega* de las instrucciones:

| Aspecto | Dónde está |
|---|---|
| **1. Código fuente** | `bank-bff/` — proyecto Maven multi-módulo, y en <https://github.com/FcoXavierParra/PBY2203_S5_FPARRA> |
| **2. Documentación** | este `README.md` |
| **3. Evidencia de ejecución** | `evidencias/` — salidas de consola de cada API |

---

## 1. La estrategia elegida, y por qué

La guía plantea tres estrategias. Esta entrega usa la primera: **backends independientes por
cada tipo de cliente**.

| estrategia | por qué sí / por qué no |
|---|---|
| **Backends independientes** | **Elegida.** Las instrucciones son literales: *"cada cliente deberá tener su propio Backend"*. Tres aplicaciones desplegables, en tres puertos, que se levantan y caen por separado |
| Endpoints personalizados en un backend | Más barato de construir, pero un solo proceso no son tres backends. La personalización quedaría, la independencia no |
| BFF sobre microservicios | El backend de este proyecto no está desagregado en microservicios, así que la capa de composición no tendría qué componer |

La guía menciona que la estrategia elegida *"puede implicar mantener repositorios de código
completamente independientes"* — **puede**, no debe. Lo que el patrón exige es que cada
cliente tenga su propio backend desplegable, y eso se cumple con tres artefactos ejecutables
distintos. Tres repositorios habrían agregado fricción de revisión sin agregar nada
demostrable, y el enunciado además pide entregar todo en una misma carpeta comprimida.

```
bank-bff/
├── bank-core/        dominio compartido — librería, no aplicación
├── bank-seguridad/   emisión y validación de JWT — librería, no aplicación
├── bff-web/          Spring Boot  https://localhost:8081
├── bff-movil/        Spring Boot  https://localhost:8082
└── bff-cajero/       Spring Boot  https://localhost:8083
```

### Dónde están las fronteras

`bank-core` deliberadamente **no** incluye `spring-boot-starter-web`. Si pudiera exponer
endpoints, nada impediría que la lógica de un canal terminara ahí y el patrón se volviera
decorativo.

`bank-seguridad` es infraestructura transversal, no dominio: por eso no vive en `bank-core`,
que modela cuentas y transacciones y no sabe nada de canales ni credenciales. Y lo que **no**
decide es tan importante como lo que decide: no fija quién puede emitir un token, ni con qué
clave, ni cuánto dura. Eso lo configura cada BFF, y es lo que permite que los canales tengan
políticas distintas sobre el mismo mecanismo.

La misma línea separa las reglas de negocio de las reglas de canal, y el caso más claro es el
retiro:

| | vive en | por qué |
|---|---|---|
| Que el saldo alcance, de forma atómica | `bank-core` | Es verdad del banco. Cualquier canal futuro la hereda sin pedirla |
| Múltiplo de $10.000, tope de $200.000 | `bff-cajero` | Es política del punto de atención. Un cajero entrega billetes; la web no tiene esa restricción |

Si el límite del cajero viviera en el core, se le impondría a todos. Si la validación de
saldo viviera en el BFF, habría que repetirla en cada canal y bastaría olvidarla una vez para
permitir un descubierto.

---

## 2. Personalización por canal

Es el criterio central y se demuestra con datos, no con código. `comparar_canales.ps1` pide
**la misma cuenta** por los tres canales y mide cada respuesta:

| canal | endpoint | campos | contenido |
|---|---|---:|---|
| web | `GET /api/web/cuentas/{id}` | 13 | ficha completa, agregados anuales y movimientos con detalle |
| móvil | `GET /api/movil/cuentas/{id}/resumen` | 4 | saldo, moneda y últimos 5 movimientos con 3 campos |
| cajero | `GET /api/cajero/saldo` | 2 | saldo disponible y moneda |

Las cifras de la corrida —tamaño **y tiempo de respuesta**— están en `evidencias/`.

**El móvil no recibe el nombre del titular ni la edad.** No es un olvido: el dueño del
teléfono ya sabe cómo se llama, y son datos personales que no hacen falta para pintar esa
pantalla. Menos datos en tránsito es también menos superficie expuesta.

**El cajero recibe un solo número.** Un terminal en la calle muestra una cifra y no tiene
dónde poner nada más; enviarle el nombre del cliente sería exponer datos personales en un
equipo público para no dibujarlos nunca.

### El límite lo pone el servidor, no el cliente

El canal móvil no acepta parámetros de tamaño. Es una decisión, no una limitación: si el
cliente pudiera pedir *todas* las transacciones, el ahorro de ancho de banda dependería de
que la app se porte bien en cada versión que se publique. El BFF garantiza el tope desde el
servidor, que es donde el patrón dice que tiene que estar.

### Superficie expuesta

La personalización no es solo cuánto se entrega, también **qué operaciones existen**:

| operación | web | móvil | cajero |
|---|:-:|:-:|:-:|
| ficha completa de cuenta | sí | — | — |
| estado de cuenta anual | sí | — | — |
| listado de la cartera | sí (rol ejecutivo) | — | — |
| transacciones paginadas | sí | — | — |
| resumen y últimos movimientos | — | sí | — |
| consulta de saldo | — | — | sí |
| **retiro de efectivo** | — | — | **sí** |

Cuatro endpoints en web, dos en móvil, tres en cajero. La cantidad no es casual: el canal con
más superficie es el que corre en un equipo de confianza, y el que menos tiene es el que está
en la calle. Cada endpoint que no existe es superficie que no hay que defender.

---

## 3. Seguridad

Las instrucciones piden *"considerar HTTPS, Autenticación y Autorización"*. Las tres están, y
ninguna de las tres es igual entre canales.

### HTTPS con un certificado por canal

Los tres BFF sirven sobre TLS 1.2/1.3, cada uno con **su propio certificado**. No comparten
keystore a propósito: son tres servicios que se despliegan por separado, y compartir la clave
privada significaría que comprometer el cajero —el equipo expuesto en la calle— entregaría
también la identidad del canal web. Un certificado por servicio es lo que permite rotar o
revocar uno sin tocar los otros.

**Los certificados no están en el repositorio.** Un keystore contiene una clave privada, y
versionarla la vuelve inútil como credencial: quien clone el repositorio podría suplantar al
servidor. `certs/` está en `.gitignore` y `generar_certificados.ps1` los recrea en un
comando, que `levantar.ps1` invoca solo si faltan.

### Autenticación: tres mecanismos para tres amenazas

| canal | cómo entra | qué acredita | token | duración |
|---|---|---|---|---|
| **web** | `POST /login` con usuario y clave | una **persona** | JWT con roles | 8 horas |
| **móvil** | `POST /registro` con token de dispositivo | el **aparato** registrado | JWT | 30 minutos |
| **cajero** | `POST /sesion` con clave de terminal **+** tarjeta y PIN | el **equipo** y luego el **cliente** | JWT + registro de vigencia | 2 minutos |

**Cada canal firma con su propia clave.** Es la propiedad de seguridad central del diseño: un
token emitido por el canal web es rechazado por el BFF del cajero, porque la firma no coincide
y el emisor declarado tampoco. Si los tres compartieran secreto, un token robado del teléfono
—el cliente más expuesto, en un aparato que se pierde— serviría para operar contra el cajero,
que es el que entrega dinero.

**Las duraciones son del canal, no del mecanismo.** Un navegador de escritorio tiene a alguien
trabajando durante horas; un teléfono se pierde y suele quedar desbloqueado en el bolsillo de
quien lo encontró; un cajero atiende a una persona parada en la calle que se va sin cerrar la
sesión.

### Autorización: roles solo donde tienen sentido

El canal web distingue `CLIENTE` de `EJECUTIVO` —recorrer la cartera completa es atribución de
ejecutivo—. El móvil no tiene roles porque la identidad es el aparato, y no existe un
equivalente móvil de "ejecutivo". El cajero encadena dos: sin `TERMINAL` no se puede ni
intentar un PIN, y sin `SESION` no se consulta ni se retira.

### El caso especial del cajero: revocación

Un JWT es autocontenido, y esa es su virtud —escala sin estado— y su limitación: **no se puede
invalidar antes de que expire**. Para el canal web o el móvil eso es aceptable. Para un cajero
no: cuando el dispensador entrega los billetes, la sesión tiene que morir en ese instante, no
dos minutos después, porque el cliente ya se dio vuelta y se fue.

Por eso el cajero suma un registro de sesiones vigentes sobre el JWT. El token aporta firma,
emisor y caducidad; el registro aporta lo único que un token autocontenido no puede dar:
revocación inmediata. El costo es explícito —**ese canal deja de ser sin estado**— y se acepta
porque un cajero atiende a una persona a la vez durante dos minutos, no a miles de sesiones
concurrentes.

### Alcance de lo implementado

Los mecanismos son reales pero simplificados, y conviene decirlo:

| | aquí | en producción |
|---|---|---|
| certificados | autofirmados, generados por script | emitidos por una CA, o TLS terminado en el balanceador |
| firma de tokens | HMAC simétrico, secreto en configuración | RS256 asimétrico, secretos en un gestor tipo Vault |
| usuarios del canal web | en memoria | OAuth2/OIDC contra el directorio corporativo |
| token de dispositivo | estático, en configuración | emitido por dispositivo, con rotación y *attestation* |
| clave del terminal | cabecera con valor de configuración | certificado de cliente sobre TLS mutuo |
| registro de sesiones | en memoria | almacén compartido (Redis) si hay varios nodos |

---

## 4. Origen de los datos

De <https://github.com/KariVillagran/bank_legacy_data>, carpeta `data/semana_3`, que es el
dataset oficial vigente del repositorio. Se verificó que **no existe** una carpeta
`semana_4`: `data/semana_3/transacciones.csv` responde y `data/semana_4/` da 404.

Los archivos están en `bank-core/src/main/resources/data/` y esa es la ruta **por defecto**,
sin necesidad de pasar ningún parámetro.

### Datos sucios: corregir, descartar o tolerar

El dataset trae errores a propósito. La carga distingue cuatro casos:

- **Corregible** — `03-04-2024` y `04/05/2024` se normalizan a fecha ISO. El orden de los
  campos es inequívoco.
- **Perdido** — un monto o un id vacío descarta la fila. Inventar un cero cambiaría los saldos
  que después muestran los tres BFF.
- **Tolerable** — una edad ausente o fuera de rango se acepta como desconocida en vez de botar
  la cuenta: la edad no afecta al saldo. El canal web la reporta como `observacionCalidad`.
- **Sin sentido de negocio** — una transacción de monto cero o negativo se descarta. No es un
  dato dañado sino una fila que no representa nada, y sumarla falsearía los totales.

Ese último criterio es el mismo que aplica el `ItemProcessor` del batch de la Experiencia 1, y
está repetido aquí a propósito: contra Oracle nunca hace falta —el batch ya filtró esas filas—
pero el montaje sin infraestructura lee los CSV crudos, y sin la regla los dos montajes
mostrarían datos distintos para el mismo dataset.

---

## 5. Cómo ejecutar

### Requisitos

- JDK 17 o superior (probado con Temurin 21)
- Maven 3.9+

No hace falta base de datos ni Docker: los tres BFF comparten **una sola** H2 en archivo, que
el primero en arrancar puebla con el dataset oficial y los otros dos encuentran ya lista.
`levantar.ps1` la borra antes de cada corrida, para que toda ejecución parta de los mismos
datos y no del saldo que dejó la anterior.

```powershell
mvn install -DskipTests
.\levantar.ps1              # genera los certificados si faltan, arranca los tres
.\comparar_canales.ps1      # genera la evidencia
.\levantar.ps1 -Detener     # los baja
```

Que haya que lanzar **tres procesos** no es un inconveniente del montaje: es la consecuencia
visible de la estrategia elegida. Si se pudieran levantar todos con un solo comando
compartiendo JVM, no serían backends separados.

### Probar a mano

Los certificados son autofirmados, así que `curl` necesita `-k` para aceptarlos:

```powershell
# Web: login, y despues el token
curl -k -X POST https://localhost:8081/api/web/login `
     -H "Content-Type: application/json" `
     -d '{\"usuario\":\"ejecutivo\",\"clave\":\"ejecutivo123\"}'
curl -k -H "Authorization: Bearer <token>" https://localhost:8081/api/web/cuentas/101

# Movil: registro del dispositivo, y despues el token
curl -k -X POST https://localhost:8082/api/movil/registro `
     -H "Content-Type: application/json" -H "X-Device-Token: token-movil-demo-2026" `
     -d '{\"deviceId\":\"android-01\"}'

# Cajero: terminal + PIN, y despues terminal + token en cada peticion
curl -k -X POST https://localhost:8083/api/cajero/sesion `
     -H "Content-Type: application/json" -H "X-ATM-Terminal: atm-key-demo-2026" `
     -d '{\"cuentaId\":101,\"pin\":\"1234\"}'
```

Credenciales de demostración: `cliente/cliente123` y `ejecutivo/ejecutivo123` en web; PIN
`1234` en el cajero.

### Contra Oracle

El perfil `oracle` apunta los tres BFF a la misma Autonomous Database que pobló el batch de la
Experiencia 1:

```powershell
$env:ORACLE_PASSWORD = 'la-clave'     # comillas SIMPLES
.\levantar.ps1 -Perfil oracle
```

Las credenciales **no están en el repositorio**: el perfil las toma de variables de entorno.
Comillas simples porque con dobles PowerShell expande las `$variables` que haya dentro y envía
una contraseña distinta de la escrita; el síntoma es un `ORA-01017` con la clave correcta.

`levantar.ps1` prueba **una sola conexión** antes de lanzar los tres. No es un lujo: cada BFF
reintenta tres veces al inicializar su pool, así que una credencial equivocada costaría nueve
intentos de login, y Autonomous Database bloquea la cuenta `ADMIN` a los diez.

---

## 6. Los dos montajes

| | perfil por defecto | perfil `oracle` |
|---|---|---|
| base de datos | **una sola** H2 en archivo, compartida | **una sola** Autonomous Database |
| origen de los datos | los CSV oficiales, al arrancar | lo que dejó el batch de la Experiencia 1 |
| requisitos | ninguno | credenciales de Oracle |
| retiro del cajero | **visible desde los tres canales** | **visible desde los tres canales** |
| `saldo_final`, `anomalia` | vacíos: ningún batch los calculó | calculados por la Experiencia 1 |

El montaje por defecto existe para que el proyecto se pueda clonar y ejecutar sin
infraestructura previa. Lo que cambia entre los dos perfiles es **de dónde salen los datos**,
no cómo se reparte el trabajo entre los canales: en ambos los tres BFF leen la misma base, y la
comparación entre ellos da lo mismo.

Que compartan base **no rompe el patrón**. Lo que BFF separa es la capa que sirve a cada
frontend —qué consulta, qué agrega, qué expone, cómo autentica—, no el almacenamiento; si
tampoco compartieran datos serían tres sistemas distintos y no tres fachadas del mismo banco.

En una versión anterior cada BFF levantaba su propia H2 **en memoria**, y eso tenía una
consecuencia que arruinaba la demostración: un retiro hecho por el cajero no se veía desde la
web ni desde el móvil, porque eran tres copias separadas de los mismos datos. La H2 en archivo
con `AUTO_SERVER` —tres procesos abren el mismo archivo y el primero hace de servidor— quita
esa limitación sin agregar un solo requisito de instalación.

### Los saldos cambian al generar evidencia, y está bien

`comparar_canales.ps1` ejecuta un **retiro real** para evidenciar el endpoint del cajero, así
que cada corrida deja la cuenta usada con $10.000 menos. Que ese débito **quede** es justamente
lo que permite demostrar la coherencia entre canales: web y móvil informan el saldo nuevo sin
que nadie los haya notificado.

Con el perfil por defecto la base vuelve a su estado original en cada arranque, porque
`levantar.ps1` borra el archivo antes de lanzar los tres. Contra Oracle persiste, porque es una
base de verdad.

No se incluye un script de repoblación, y es deliberado: los datos de Oracle los produce el
batch de la Experiencia 1, que además de cargarlos calcula intereses y marcas de anomalía. Un
repoblador propio duplicaría esa lógica a medias y las dos copias divergirían a la primera
corrección.

---

## 7. Evidencia incluida

En `evidencias/`:

| archivo | motor | qué aporta |
|---|---|---|
| `01_comparacion_canales_oracle.txt` | Autonomous Database | la **continuidad con la Experiencia 1**: los saldos traen los intereses ya aplicados y los agregados anuales vienen calculados por el batch, no por el BFF |
| `01_comparacion_canales_h2.txt` | H2 en archivo | la corrida **reproducible sin credenciales**, con la sección de coherencia entre canales |

Ambos archivos contienen lo mismo en estructura: la misma cuenta por los tres canales con
tamaño y tiempo de respuesta, la superficie expuesta, el inventario de las nueve APIs y los
tres casos del retiro. Se incluyen los dos a propósito, porque prueban cosas distintas: el de
Oracle que el sistema se apoya en datos reales producidos por la entrega anterior, y el de H2
que cualquiera puede reproducir la comparación completa clonando el repositorio.

La diferencia visible entre ambos está en los campos que el batch calcula. Contra Oracle,
`cantidadMovimientos` es 30 y `totalDepositos` 18.000; contra H2 los dos son 0, porque ningún
batch corrió sobre esa base. Los BFF **sirven** ese cálculo, no lo rehacen.

**El nombre lleva el motor, y no es decorativo.** `comparar_canales.ps1` detecta el perfil
activo leyendo el log de arranque —no lo declara a mano— y nombra el archivo según lo que
encontró. Así queda escrito en la evidencia misma contra qué motor corrió, sin que nadie lo
tenga que afirmar aparte.

La sección que cierra el argumento del patrón es **Coherencia entre canales**: el cajero debita
$10.000 y acto seguido se le pregunta el saldo a los tres. Los tres responden lo mismo, cada
uno con su propia forma —el web dentro de una ficha de 13 campos con agregados anuales, el
móvil dentro de un resumen de 4, el cajero solo, en 43 bytes—. **El dato es uno; la
representación es del canal.**

Esa sección aparece en `01_comparacion_canales_h2.txt` y no en el de Oracle, que se generó
antes de agregarla. Volver a producirlo es un comando —`.\levantar.ps1 -Perfil oracle` y
`.\comparar_canales.ps1`—, pero exige las credenciales, y la evidencia contra Oracle ya prueba
lo que le toca probar: que los datos vienen del batch de la Experiencia 1.

### Sobre los tiempos de respuesta

Se mide la **mediana de siete llamadas**, después de una de calentamiento que paga el
handshake TLS y el JIT. La mediana y no el promedio: un pico ocasional del sistema operativo
arrastra un promedio y no mueve una mediana.

---

## 8. Estructura del código

```
bank-bff/
├── generar_certificados.ps1        un certificado TLS por canal
├── levantar.ps1                    arranca, verifica y detiene los tres BFF
├── comparar_canales.ps1            genera la evidencia comparativa
├── herramientas/ProbarConexion.java  sonda de UNA conexion a Oracle
├── pom.xml                         padre multi-módulo
│
├── bank-core/                      DOMINIO COMPARTIDO (librería)
│   └── cl/duoc/bank/core/
│       ├── dominio/                    Cuenta, Transaccion, MovimientoAnual
│       ├── repositorio/                los tres repositorios JPA
│       ├── carga/CargadorDatos.java    carga y saneamiento del dataset oficial
│       └── servicio/
│           ├── ConsultaService.java    consultas sin forma de canal
│           └── OperacionService.java   retiro atómico con validación de saldo
│
├── bank-seguridad/                 JWT COMPARTIDO (librería)
│   └── cl/duoc/bank/seguridad/
│       ├── TokenService.java           emite y valida; una instancia por canal
│       └── FiltroJwt.java              mismo filtro, política de cada canal
│
├── bff-web/                        :8081
│   ├── api/          CuentaWebController, LoginWebController
│   ├── dto/          6 DTO, los más ricos de los tres canales
│   └── config/       SeguridadWebConfig — JWT con roles
│
├── bff-movil/                      :8082
│   ├── api/          ResumenMovilController, RegistroMovilController
│   ├── dto/          3 DTO, mínimos
│   └── config/       SeguridadMovilConfig — JWT de dispositivo
│
└── bff-cajero/                     :8083
    ├── api/          CajeroController
    ├── dto/          sesión y operaciones
    └── config/
        ├── SeguridadCajeroConfig.java   terminal + JWT + revocación
        └── SesionCajeroService.java     registro de sesiones vigentes
```

La organización responde al criterio de extensibilidad: un módulo por backend, y dentro de
cada uno la misma tríada `api` / `dto` / `config`. Los DTO de un canal no son visibles desde
otro, que es lo que impide que la forma de una respuesta se filtre entre canales.

**Agregar un cuarto canal** —una API para partners, por ejemplo— sería un módulo nuevo con esa
misma tríada, su certificado, su clave de firma y su duración de token. No habría que tocar
`bank-core`, ni `bank-seguridad`, ni ninguno de los tres BFF existentes. Eso es lo que la
estructura compra.

---

## 9. Continuidad con la Experiencia 1

El dominio y el dataset vienen del proyecto Spring Batch de las semanas 1 a 3: las mismas
cuentas, transacciones y movimientos anuales del Banco XYZ. Con el perfil `oracle` los tres
BFF leen la base que aquel batch dejó poblada, y sirven lo que **el batch calculó** —el
`saldo_final` con intereses aplicados, la marca de anomalía— en lugar de recalcularlo.

Compartir el origen de datos **no** rompe el patrón: lo que BFF separa es la capa que sirve a
cada frontend, no el almacenamiento. La Experiencia 1 escribe; la Experiencia 2 sirve.
