package cl.duoc.bank.bff.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ficha completa de una cuenta para el canal web.
 *
 * Doce campos mas el detalle de los ultimos movimientos, contra los cuatro del
 * resumen movil y el uno del cajero. No es desprolijidad: la pagina de cuenta de
 * un navegador de escritorio muestra la ficha, los agregados anuales y la tabla
 * de movimientos en la misma pantalla. Devolver eso en una sola respuesta evita
 * las tres o cuatro llamadas que el frontend tendria que encadenar para armarla.
 *
 * Los campos calculados -totales, promedio, cantidad de movimientos- se resuelven
 * aca y no en el frontend. Es el punto del patron: la logica de presentacion vive
 * en el BFF, no duplicada en cada cliente.
 *
 * Comparar esta clase con ResumenMovilDto es la forma mas rapida de ver el patron
 * en accion: misma cuenta, mismo origen de datos, dos formas deliberadamente
 * distintas.
 */
public record CuentaWebDto(
        Long cuentaId,
        String titular,
        String tipoCuenta,
        BigDecimal saldo,
        String saldoFormateado,
        Integer edad,
        String segmentoEtario,
        int cantidadMovimientos,
        BigDecimal totalDepositos,
        BigDecimal totalRetiros,
        BigDecimal montoPromedio,
        String observacionCalidad,

        /**
         * Movimientos recientes con TODO su detalle: monto formateado, marca de
         * anomalia y aviso de tipo mal clasificado. El canal movil devuelve los
         * mismos movimientos con tres campos y sin marcas, porque en un telefono
         * esa tabla no se dibuja.
         */
        List<TransaccionWebDto> movimientosRecientes) {
}
