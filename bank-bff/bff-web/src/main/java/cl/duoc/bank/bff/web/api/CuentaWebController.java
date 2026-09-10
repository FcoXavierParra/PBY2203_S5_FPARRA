package cl.duoc.bank.bff.web.api;

import cl.duoc.bank.bff.web.dto.CuentaWebDto;
import cl.duoc.bank.bff.web.dto.EstadoAnualWebDto;
import cl.duoc.bank.bff.web.dto.MovimientoAnualWebDto;
import cl.duoc.bank.bff.web.dto.PaginaWebDto;
import cl.duoc.bank.bff.web.dto.TransaccionWebDto;
import cl.duoc.bank.core.dominio.Cuenta;
import cl.duoc.bank.core.dominio.MovimientoAnual;
import cl.duoc.bank.core.dominio.Transaccion;
import cl.duoc.bank.core.servicio.ConsultaService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * API del canal WEB.
 *
 * Todo lo que este controlador expone se decidio mirando al navegador de
 * escritorio: respuestas completas, paginacion configurable y campos calculados
 * listos para pintar. Los tres BFF consultan el mismo ConsultaService del modulo
 * comun; lo que cambia -y es donde vive el patron- es que se selecciona, como se
 * agrega y con que forma se devuelve.
 */
@RestController
@RequestMapping("/api/web")
@RequiredArgsConstructor
public class CuentaWebController {

    private static final int TAMANO_PAGINA_MAXIMO = 200;

    private final ConsultaService consulta;

    @Value("${bank.web.umbral-anomalia:2500}")
    private BigDecimal umbralAnomalia;

    /** Listado completo de cuentas, con sus agregados ya resueltos. */
    @GetMapping("/cuentas")
    public List<CuentaWebDto> listarCuentas() {
        return consulta.listarCuentas().stream().map(this::aDto).toList();
    }

    @GetMapping("/cuentas/{cuentaId}")
    public ResponseEntity<CuentaWebDto> detalle(@PathVariable Long cuentaId) {
        Optional<Cuenta> cuenta = consulta.buscarCuenta(cuentaId);
        return cuenta.map(c -> ResponseEntity.ok(aDto(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Estado de cuenta anual con el desglose movimiento por movimiento.
     *
     * Es la respuesta mas pesada de los tres canales y solo existe aqui: en un
     * telefono nadie audita un anio de movimientos, y un cajero automatico no
     * tiene por que poder pedirlo.
     */
    @GetMapping("/cuentas/{cuentaId}/estado-anual")
    public ResponseEntity<EstadoAnualWebDto> estadoAnual(@PathVariable Long cuentaId,
                                                         @RequestParam(defaultValue = "2024") int anio) {

        Optional<Cuenta> cuenta = consulta.buscarCuenta(cuentaId);
        if (cuenta.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<MovimientoAnual> movimientos = consulta.movimientosDe(cuentaId).stream()
                .filter(m -> m.getFecha().getYear() == anio)
                .sorted(Comparator.comparing(MovimientoAnual::getFecha))
                .toList();

        return ResponseEntity.ok(new EstadoAnualWebDto(
                cuentaId,
                cuenta.get().getNombre(),
                anio,
                totalPor(movimientos, "deposito"),
                totalPor(movimientos, "retiro"),
                totalPor(movimientos, "compra"),
                totalPor(movimientos, "deposito")
                        .subtract(totalPor(movimientos, "retiro"))
                        .subtract(totalPor(movimientos, "compra")),
                movimientos.size(),
                movimientos.stream()
                        .map(m -> new MovimientoAnualWebDto(
                                m.getFecha(), m.getTipoTransaccion(), m.getMonto(), m.getDescripcion()))
                        .toList()));
    }

    /**
     * Transacciones paginadas por rango de fechas.
     *
     * El tamano de pagina es del cliente pero con techo: un navegador puede pedir
     * 200 filas de una vez sin problema, y dejarlo abierto permitiria que una
     * peticion se llevara las mil de golpe.
     */
    @GetMapping("/transacciones")
    public PaginaWebDto<TransaccionWebDto> transacciones(
            @RequestParam(defaultValue = "2024-01-01") LocalDate desde,
            @RequestParam(defaultValue = "2024-12-31") LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "25") int tamano) {

        int tamanoEfectivo = Math.min(Math.max(tamano, 1), TAMANO_PAGINA_MAXIMO);
        Page<Transaccion> resultado = consulta.transaccionesPaginadas(desde, hasta, pagina, tamanoEfectivo);

        return new PaginaWebDto<>(
                resultado.getContent().stream().map(this::aDto).toList(),
                resultado.getNumber(),
                resultado.getSize(),
                resultado.getTotalElements(),
                resultado.getTotalPages(),
                resultado.hasNext());
    }

    // ------------------------------------------------------------ MAPEO

    private CuentaWebDto aDto(Cuenta c) {
        List<MovimientoAnual> movimientos = consulta.movimientosDe(c.getCuentaId());

        BigDecimal depositos = totalPor(movimientos, "deposito");
        BigDecimal retiros = totalPor(movimientos, "retiro");
        BigDecimal promedio = movimientos.isEmpty()
                ? BigDecimal.ZERO
                : movimientos.stream().map(MovimientoAnual::getMonto)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(movimientos.size()), 2, RoundingMode.HALF_UP);

        return new CuentaWebDto(
                c.getCuentaId(),
                c.getNombre(),
                c.getTipo(),
                c.getSaldoFinal(),
                formatear(c.getSaldoFinal()),
                c.getEdad(),
                segmento(c.getEdad()),
                movimientos.size(),
                depositos,
                retiros,
                promedio,
                observacion(c),
                // Los mismos movimientos que el canal movil devuelve con tres
                // campos, aqui con siete y con las marcas que la tabla web
                // necesita para resaltarlos.
                consulta.ultimasTransacciones().stream().map(this::aDto).toList());
    }

    private TransaccionWebDto aDto(Transaccion t) {
        return new TransaccionWebDto(
                t.getId(),
                t.getFecha(),
                t.getMonto(),
                formatear(t.getMonto()),
                t.getTipo(),
                esAnomalia(t),
                "desconocido".equals(t.getTipo()));
    }

    /**
     * La marca de anomalia la calcula el batch de la Experiencia 1 y queda
     * guardada en la tabla. Cuando existe, manda: si cada BFF volviera a
     * decidirlo con un umbral propio, tres canales podrian discrepar sobre la
     * misma transaccion.
     *
     * El umbral local es solo el respaldo para cuando los datos se cargaron
     * desde los CSV y ningun batch los proceso -el modo con el que el proyecto
     * se ejecuta sin infraestructura-. Ahi la columna viene en false para todas
     * las filas, y sin este respaldo la tabla web no marcaria ninguna.
     */
    private boolean esAnomalia(Transaccion t) {
        if (t.isAnomalia()) {
            return true;
        }
        return t.getMonto() != null && t.getMonto().compareTo(umbralAnomalia) > 0;
    }

    private static BigDecimal totalPor(List<MovimientoAnual> movimientos, String tipo) {
        return movimientos.stream()
                .filter(m -> tipo.equalsIgnoreCase(m.getTipoTransaccion()))
                .map(MovimientoAnual::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String formatear(BigDecimal monto) {
        return monto == null ? "-" : String.format(Locale.forLanguageTag("es-CL"), "$%,.0f", monto);
    }

    /**
     * Segmento etario para la interfaz. El dataset trae edades ausentes o fuera
     * de rango, que la carga deja en null; aqui se declaran como tales en vez de
     * asignarles un tramo inventado.
     */
    private static String segmento(Integer edad) {
        if (edad == null) {
            return "sin informacion";
        }
        if (edad < 30) {
            return "joven";
        }
        return edad < 60 ? "adulto" : "senior";
    }

    /** Aviso de calidad de dato, para que la interfaz pueda marcarlo. */
    private static String observacion(Cuenta c) {
        if ("desconocido".equals(c.getTipo())) {
            return "tipo de cuenta no clasificado en el origen";
        }
        if (c.getEdad() == null) {
            return "edad ausente o fuera de rango en el origen";
        }
        return null;
    }
}
