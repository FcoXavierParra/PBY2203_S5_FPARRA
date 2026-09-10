package cl.duoc.bank.bff.cajero.api;

import cl.duoc.bank.bff.cajero.config.SesionCajeroService;
import cl.duoc.bank.bff.cajero.dto.OperacionDto;
import cl.duoc.bank.bff.cajero.dto.SesionDto;
import cl.duoc.bank.core.dominio.Cuenta;
import cl.duoc.bank.core.servicio.ConsultaService;
import cl.duoc.bank.bff.cajero.config.SeguridadCajeroConfig;
import cl.duoc.bank.core.servicio.OperacionService;
import cl.duoc.bank.seguridad.TokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * API del canal CAJERO AUTOMATICO.
 *
 * Tres endpoints y ni uno mas. No hay listados, ni historial, ni datos del
 * titular: un equipo publico solo necesita saber cuanto hay y entregar plata.
 * Cada endpoint que no existe es superficie que no hay que defender.
 *
 * LAS REGLAS DE ESTE CANAL VIVEN AQUI
 * -----------------------------------
 * El monto multiplo de 10.000 y el tope por operacion son politicas del punto de
 * atencion: un cajero entrega billetes y no tiene como dar $3.750. El modulo
 * comun no sabe nada de eso -alli solo se valida que el saldo alcance, que es
 * verdad del banco y no del cajero-. Ver el javadoc de OperacionService.
 */
@Slf4j
@RestController
@RequestMapping("/api/cajero")
@RequiredArgsConstructor
public class CajeroController {

    /** Denominacion minima que entrega el dispensador. */
    private static final BigDecimal MULTIPLO = new BigDecimal("10000");

    /** Tope por operacion del canal, independiente del saldo disponible. */
    private static final BigDecimal TOPE_POR_OPERACION = new BigDecimal("200000");

    private final ConsultaService consulta;
    private final OperacionService operaciones;
    private final SesionCajeroService sesiones;
    private final TokenService tokens;

    /**
     * Tarjeta y PIN a cambio de un token de sesion.
     *
     * Un PIN incorrecto y una cuenta inexistente devuelven exactamente la misma
     * respuesta 401. Distinguirlas le diria a quien prueba tarjetas al azar
     * cuales existen, que es la mitad del trabajo de un ataque.
     */
    @PostMapping("/sesion")
    public ResponseEntity<SesionDto.Respuesta> abrirSesion(@Valid @RequestBody SesionDto.Solicitud solicitud) {

        Optional<Cuenta> cuenta = consulta.buscarCuenta(solicitud.cuentaId());
        if (cuenta.isEmpty() || !sesiones.pinCorrecto(solicitud.pin())) {
            log.warn("Intento de sesion rechazado para la tarjeta {}", solicitud.cuentaId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // El JWT lleva la cuenta como sujeto, firmada. El terminal no puede
        // decir sobre que cuenta opera: viene del token, no de la peticion.
        String token = tokens.emitir(new TokenService.Identidad(
                String.valueOf(solicitud.cuentaId()),
                SeguridadCajeroConfig.CANAL,
                List.of("SESION")));

        // Ademas se registra como vigente, para poder revocarlo al entregar el
        // dinero. Un JWT por si solo no se puede invalidar antes de expirar.
        sesiones.registrar(token, tokens.duracionSegundos());

        return ResponseEntity.ok(new SesionDto.Respuesta(token, tokens.duracionSegundos()));
    }

    /**
     * Saldo disponible. Un campo.
     *
     * La cuenta sale del token de sesion, no de la peticion: el terminal no
     * puede pedir el saldo de una cuenta distinta de la que abrio sesion.
     */
    @GetMapping("/saldo")
    public ResponseEntity<OperacionDto.Saldo> saldo(Authentication autenticacion) {
        Long cuentaId = (Long) autenticacion.getPrincipal();
        return consulta.buscarCuenta(cuentaId)
                .map(c -> ResponseEntity.ok(new OperacionDto.Saldo(c.getSaldoFinal(), "CLP")))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Retiro de efectivo. */
    @PostMapping("/retiro")
    public ResponseEntity<OperacionDto.ResultadoRetiro> retirar(
            @Valid @RequestBody OperacionDto.SolicitudRetiro solicitud,
            Authentication autenticacion) {

        // La cuenta y el token salen de la autenticacion, que los dejo puestos
        // FiltroRevocacion tras validar firma y vigencia.
        //
        // Antes el token se recibia en una cabecera propia declarada con
        // @RequestHeader obligatorio. Al migrar a Authorization: Bearer esa
        // cabecera dejo de existir, Spring lanzaba MissingRequestHeaderException,
        // despachaba a /error, y como la cadena de seguridad termina en denyAll()
        // el cliente veia un 403 que parecia un problema de permisos y no de
        // parametros. Tomarlos del contexto elimina esa posibilidad de
        // desincronizacion entre lo que valida el filtro y lo que exige el metodo.
        Long cuentaId = (Long) autenticacion.getPrincipal();
        String token = (String) autenticacion.getCredentials();
        BigDecimal monto = solicitud.monto();

        // --- Reglas del canal, antes de tocar el dominio -------------------
        if (monto.remainder(MULTIPLO).compareTo(BigDecimal.ZERO) != 0) {
            return ResponseEntity.badRequest().body(new OperacionDto.ResultadoRetiro(
                    false, "MONTO_NO_DISPENSABLE",
                    "El monto debe ser multiplo de " + MULTIPLO.toPlainString(),
                    null, null));
        }
        if (monto.compareTo(TOPE_POR_OPERACION) > 0) {
            return ResponseEntity.badRequest().body(new OperacionDto.ResultadoRetiro(
                    false, "SOBRE_TOPE",
                    "El tope por operacion es " + TOPE_POR_OPERACION.toPlainString(),
                    null, null));
        }

        // --- Regla del banco: que el saldo alcance, de forma atomica -------
        OperacionService.Retiro resultado = operaciones.retirar(cuentaId, monto);

        if (!resultado.autorizado()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new OperacionDto.ResultadoRetiro(
                    false, "RECHAZADO", resultado.motivo(), null, resultado.saldoResultante()));
        }

        // La sesion se cierra al completar la operacion: el cliente ya retiro su
        // tarjeta y no hay razon para dejar el token vivo el resto de su ventana.
        sesiones.cerrar(token);

        return ResponseEntity.ok(new OperacionDto.ResultadoRetiro(
                true, "AUTORIZADO", "Retire su dinero", monto, resultado.saldoResultante()));
    }
}
