package cl.duoc.bank.seguridad;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Emite y valida los JWT de un canal.
 *
 * CADA CANAL TIENE SU PROPIA INSTANCIA, CON SU PROPIA CLAVE
 * ---------------------------------------------------------
 * Esta clase no es un singleton compartido: cada BFF crea la suya con su clave,
 * su emisor y su duracion. La consecuencia es la propiedad de seguridad mas
 * importante del diseno: **un token emitido por el canal web es rechazado por el
 * BFF del cajero**, porque esta firmado con otra clave y declara otro emisor.
 *
 * Si los tres compartieran secreto, un token robado de la aplicacion movil
 * -el cliente mas expuesto, en un telefono que el usuario puede perder- serviria
 * para operar contra el cajero, que es el canal que entrega dinero. La
 * separacion de claves es lo que impide que la superficie de ataque de un canal
 * se propague a los otros.
 *
 * La duracion tambien es del canal y no del mecanismo: la sesion de un cajero
 * dura dos minutos porque es una persona parada frente a una maquina en la
 * calle; la de un navegador dura horas porque hay alguien trabajando.
 *
 * ALCANCE: el secreto llega por configuracion y la firma es simetrica (HMAC).
 * Para un sistema real con varios emisores conviene firma asimetrica -RS256- de
 * modo que quien valida no necesite la clave capaz de firmar, y los secretos
 * irian en un gestor tipo Vault y no en un archivo de propiedades.
 */
@Slf4j
public class TokenService {

    /** Nombre del claim que identifica el canal emisor. */
    public static final String CLAIM_CANAL = "canal";

    /** Nombre del claim con los roles del portador. */
    public static final String CLAIM_ROLES = "roles";

    private final SecretKey clave;
    private final String emisor;
    private final Duration duracion;

    /**
     * @param secreto  clave de firma del canal. HS256 exige al menos 256 bits,
     *                 o sea 32 caracteres; se valida al construir para que el
     *                 error salga al arrancar y no en la primera peticion.
     * @param emisor   identificador del canal, que viaja en el claim 'iss' y se
     *                 exige al validar.
     * @param duracion cuanto vive el token de este canal.
     */
    public TokenService(String secreto, String emisor, Duration duracion) {
        byte[] bytes = secreto.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException(
                    "El secreto de " + emisor + " necesita al menos 32 caracteres para HS256, "
                            + "y tiene " + bytes.length + ".");
        }
        this.clave = Keys.hmacShaKeyFor(bytes);
        this.emisor = emisor;
        this.duracion = duracion;
        log.info("TokenService de '{}' listo: los tokens duran {}", emisor, duracion);
    }

    /**
     * Datos que viajan dentro del token.
     *
     * @param sujeto quien es el portador. Su significado cambia por canal: en web
     *               es el nombre de usuario, en movil el identificador del
     *               dispositivo y en cajero el numero de cuenta de la tarjeta.
     */
    public record Identidad(String sujeto, String canal, List<String> roles) {
    }

    /** Emite un token firmado para este canal. */
    public String emitir(Identidad identidad) {
        Instant ahora = Instant.now();
        return Jwts.builder()
                .subject(identidad.sujeto())
                .issuer(emisor)
                .claim(CLAIM_CANAL, identidad.canal())
                .claim(CLAIM_ROLES, identidad.roles())
                .issuedAt(Date.from(ahora))
                .expiration(Date.from(ahora.plus(duracion)))
                .signWith(clave)
                .compact();
    }

    /**
     * Valida un token y devuelve su identidad, o vacio si no sirve.
     *
     * Se exige el emisor ademas de la firma. La firma sola ya impediria usar un
     * token de otro canal -las claves son distintas-, pero declarar el emisor
     * hace explicito el contrato y deja el motivo del rechazo en el log.
     *
     * No se distingue entre firma invalida, token expirado y formato corrupto:
     * los tres devuelven vacio y el llamador responde 401. Detallar por que
     * fallo le diria a quien prueba tokens al azar si va por buen camino.
     */
    public Optional<Identidad> validar(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims c = Jwts.parser()
                    .verifyWith(clave)
                    .requireIssuer(emisor)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            @SuppressWarnings("unchecked")
            List<String> roles = c.get(CLAIM_ROLES, List.class);

            return Optional.of(new Identidad(
                    c.getSubject(),
                    c.get(CLAIM_CANAL, String.class),
                    roles == null ? List.of() : roles));

        } catch (JwtException | IllegalArgumentException e) {
            // Se registra el tipo, nunca el token: un JWT en un log es una
            // credencial en un log.
            log.debug("Token rechazado en '{}': {}", emisor, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public long duracionSegundos() {
        return duracion.toSeconds();
    }
}
