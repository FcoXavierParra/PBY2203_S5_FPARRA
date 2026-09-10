package cl.duoc.bank.bff.movil.api;

import cl.duoc.bank.bff.movil.config.SeguridadMovilConfig;
import cl.duoc.bank.bff.movil.dto.RegistroMovilDto;
import cl.duoc.bank.seguridad.TokenService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.util.List;

/**
 * Cambia el token de dispositivo por un token de sesion.
 *
 * Es el unico endpoint abierto del canal, y el punto donde la credencial
 * persistente se convierte en una que caduca.
 */
@Slf4j
@RestController
@RequestMapping("/api/movil")
public class RegistroMovilController {

    private final TokenService tokens;
    private final byte[] tokenDispositivo;

    public RegistroMovilController(TokenService tokens,
                                   @Value("${bank.movil.token:token-movil-demo-2026}") String tokenDispositivo) {
        this.tokens = tokens;
        this.tokenDispositivo = tokenDispositivo.getBytes();
    }

    @PostMapping("/registro")
    public ResponseEntity<RegistroMovilDto.Respuesta> registrar(
            @Valid @RequestBody RegistroMovilDto.Solicitud solicitud,
            @RequestHeader(value = "X-Device-Token", required = false) String tokenRecibido) {

        // MessageDigest.isEqual y no String.equals: la comparacion es de tiempo
        // constante, de modo que el tiempo de respuesta no revela cuantos
        // caracteres iniciales acerto quien lo intenta. Contra un endpoint
        // publico de una aplicacion movil es una precaucion barata.
        if (tokenRecibido == null
                || !MessageDigest.isEqual(tokenRecibido.getBytes(), tokenDispositivo)) {
            log.warn("Registro rechazado para el dispositivo '{}'", solicitud.deviceId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String token = tokens.emitir(new TokenService.Identidad(
                solicitud.deviceId(), SeguridadMovilConfig.CANAL, List.of("DISPOSITIVO")));

        log.info("Sesion movil abierta para el dispositivo '{}'", solicitud.deviceId());

        return ResponseEntity.ok(new RegistroMovilDto.Respuesta(token, tokens.duracionSegundos()));
    }
}
