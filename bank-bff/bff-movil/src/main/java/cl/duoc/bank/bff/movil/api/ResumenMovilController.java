package cl.duoc.bank.bff.movil.api;

import cl.duoc.bank.bff.movil.dto.MovimientoMovilDto;
import cl.duoc.bank.bff.movil.dto.ResumenMovilDto;
import cl.duoc.bank.core.dominio.Cuenta;
import cl.duoc.bank.core.dominio.Transaccion;
import cl.duoc.bank.core.servicio.ConsultaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * API del canal MOVIL.
 *
 * Dos endpoints y ninguno acepta parametros de tamano. Es una decision, no una
 * limitacion: si el cliente pudiera pedir "todas las transacciones", el ahorro de
 * ancho de banda dependeria de que la app se porte bien en cada version que se
 * publique. El BFF garantiza el limite desde el servidor, que es donde el patron
 * dice que tiene que estar.
 */
@RestController
@RequestMapping("/api/movil")
@RequiredArgsConstructor
public class ResumenMovilController {

    private final ConsultaService consulta;

    /** Pantalla de inicio: saldo y ultimos movimientos, en una sola llamada. */
    @GetMapping("/cuentas/{cuentaId}/resumen")
    public ResponseEntity<ResumenMovilDto> resumen(@PathVariable Long cuentaId) {
        Optional<Cuenta> cuenta = consulta.buscarCuenta(cuentaId);
        if (cuenta.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ResumenMovilDto(
                cuenta.get().getCuentaId(),
                cuenta.get().getSaldoFinal(),
                "CLP",
                movimientos()));
    }

    /** Ultimos movimientos. Tope fijo del servidor. */
    @GetMapping("/cuentas/{cuentaId}/movimientos")
    public ResponseEntity<List<MovimientoMovilDto>> movimientos(@PathVariable Long cuentaId) {
        if (consulta.buscarCuenta(cuentaId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(movimientos());
    }

    private List<MovimientoMovilDto> movimientos() {
        return consulta.ultimasTransacciones().stream()
                .map(ResumenMovilController::aDto)
                .toList();
    }

    private static MovimientoMovilDto aDto(Transaccion t) {
        return new MovimientoMovilDto(t.getFecha(), t.getMonto(), t.getTipo());
    }
}
