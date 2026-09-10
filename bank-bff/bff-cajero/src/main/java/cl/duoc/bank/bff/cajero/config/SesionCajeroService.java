package cl.duoc.bank.bff.cajero.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro de sesiones vigentes del cajero.
 *
 * POR QUE EXISTE, SI YA HAY JWT
 * -----------------------------
 * Un JWT es autocontenido: quien lo valida solo comprueba la firma y la fecha
 * de expiracion, sin consultar a nadie. Esa es su virtud -escala sin estado- y
 * tambien su limitacion: **no se puede invalidar antes de que expire**.
 *
 * Para el canal web o el movil eso es aceptable. Para un cajero automatico no.
 * Cuando el dispensador entrega los billetes, la sesion tiene que morir en ese
 * instante: el cliente ya se dio vuelta y se fue, y dejar el token vivo el resto
 * de su ventana significa que quien se acerque despues podria seguir operando
 * sobre esa cuenta.
 *
 * De ahi esta lista de sesiones vigentes. El JWT sigue aportando lo suyo -firma,
 * emisor, caducidad, cuenta en el sujeto-, y este registro agrega lo unico que
 * un token autocontenido no puede dar: revocacion inmediata.
 *
 * Es el patron habitual cuando se necesitan ambas cosas, y el costo es explicito:
 * este canal deja de ser sin estado. Se acepta porque un cajero atiende a una
 * persona a la vez durante dos minutos, no a miles de sesiones concurrentes.
 *
 * ALCANCE: la lista vive en memoria. Con varios BFF de cajero detras de un
 * balanceador tendria que ser un almacen compartido -Redis, por ejemplo-, o
 * habria que aceptar que la revocacion solo aplica en el nodo que la ejecuto.
 */
@Slf4j
@Service
public class SesionCajeroService {

    private final Map<String, Instant> vigentes = new ConcurrentHashMap<>();

    private final String pinValido;

    public SesionCajeroService(@Value("${bank.cajero.pin:1234}") String pinValido) {
        this.pinValido = pinValido;
    }

    public boolean pinCorrecto(String pin) {
        return pinValido.equals(pin);
    }

    /** Deja el token en la lista de vigentes. */
    public void registrar(String token, long duracionSegundos) {
        vigentes.put(token, Instant.now().plusSeconds(duracionSegundos));
        limpiarVencidas();
    }

    /**
     * Un token revocado deja de servir aunque su firma siga siendo valida y su
     * fecha de expiracion no haya llegado.
     */
    public boolean estaVigente(String token) {
        Instant vence = vigentes.get(token);
        if (vence == null) {
            return false;
        }
        if (Instant.now().isAfter(vence)) {
            vigentes.remove(token);
            return false;
        }
        return true;
    }

    /** Revoca la sesion. Se llama al completar la operacion, no al expirar. */
    public void cerrar(String token) {
        if (token != null && vigentes.remove(token) != null) {
            log.info("Sesion cerrada tras completar la operacion");
        }
    }

    /**
     * Descarta las entradas ya vencidas.
     *
     * Sin esto el mapa solo crece: cada tarjeta insertada dejaria su entrada
     * para siempre, aunque el token ya no sirva para nada.
     */
    private void limpiarVencidas() {
        Instant ahora = Instant.now();
        vigentes.entrySet().removeIf(e -> ahora.isAfter(e.getValue()));
    }
}
