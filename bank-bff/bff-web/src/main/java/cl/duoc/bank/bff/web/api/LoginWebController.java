package cl.duoc.bank.bff.web.api;

import cl.duoc.bank.bff.web.config.SeguridadWebConfig;
import cl.duoc.bank.bff.web.dto.LoginWebDto;
import cl.duoc.bank.seguridad.TokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Entrega el token del canal web a cambio de usuario y clave.
 *
 * Es el unico endpoint abierto de este BFF, y por eso el unico que un atacante
 * puede alcanzar sin credencial. De ahi las dos precauciones de abajo.
 */
@Slf4j
@RestController
@RequestMapping("/api/web")
@RequiredArgsConstructor
public class LoginWebController {

    private final UserDetailsService usuarios;
    private final PasswordEncoder encoder;
    private final TokenService tokens;

    @PostMapping("/login")
    public ResponseEntity<LoginWebDto.Respuesta> login(@Valid @RequestBody LoginWebDto.Solicitud solicitud) {

        UserDetails usuario;
        try {
            usuario = usuarios.loadUserByUsername(solicitud.usuario());
        } catch (UsernameNotFoundException e) {
            // Un usuario inexistente y una clave equivocada devuelven lo mismo.
            // Distinguirlos permitiria enumerar cuentas validas, que es la mitad
            // del trabajo de un ataque por fuerza bruta.
            return rechazar(solicitud.usuario());
        }

        if (!encoder.matches(solicitud.clave(), usuario.getPassword())) {
            return rechazar(solicitud.usuario());
        }

        List<String> roles = usuario.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        String token = tokens.emitir(new TokenService.Identidad(
                usuario.getUsername(), SeguridadWebConfig.CANAL, roles));

        log.info("Token emitido para '{}' con roles {}", usuario.getUsername(), roles);

        return ResponseEntity.ok(new LoginWebDto.Respuesta(
                token, tokens.duracionSegundos(), roles));
    }

    private ResponseEntity<LoginWebDto.Respuesta> rechazar(String usuario) {
        // Se registra el usuario intentado, jamas la clave.
        log.warn("Intento de login rechazado para '{}'", usuario);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
