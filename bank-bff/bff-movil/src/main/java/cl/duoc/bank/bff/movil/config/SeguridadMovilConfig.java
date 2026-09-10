package cl.duoc.bank.bff.movil.config;

import cl.duoc.bank.seguridad.FiltroJwt;
import cl.duoc.bank.seguridad.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Duration;

/**
 * Seguridad del canal MOVIL: el dispositivo se registra, despues usa token.
 *
 * DOS CREDENCIALES CON PAPELES DISTINTOS
 * --------------------------------------
 *   1. El token de DISPOSITIVO (X-Device-Token) acredita que la aplicacion
 *      instalada en ese telefono es legitima. Es de larga vida y solo sirve
 *      para una cosa: pedir un token de sesion en /registro.
 *   2. El JWT de SESION es lo que autoriza las consultas, y dura 30 minutos.
 *
 * Separarlos importa. Con una sola credencial de larga vida, quien la
 * interceptara podria consultar la cuenta indefinidamente; asi, lo que viaja en
 * cada peticion caduca solo, y la credencial persistente solo aparece al
 * registrarse.
 *
 * POR QUE 30 MINUTOS Y NO 8 HORAS COMO EL CANAL WEB
 * -------------------------------------------------
 * Un telefono se pierde y se roba con una facilidad que un computador de
 * escritorio no tiene, y suele quedar desbloqueado en el bolsillo de quien lo
 * encontro. La ventana de exposicion de un token robado tiene que ser mas corta.
 * Y no hay roles: la identidad es el aparato, y un aparato registrado ve su
 * cuenta y nada mas.
 *
 * ALCANCE: el token de dispositivo es estatico y esta en configuracion. En
 * produccion se emitiria uno por dispositivo al instalar la aplicacion, con
 * rotacion, revocacion y attestation del sistema operativo.
 */
@Configuration
public class SeguridadMovilConfig {

    /** Nombre del canal. Viaja en el token y el filtro lo exige. */
    public static final String CANAL = "movil";

    @Bean
    public TokenService tokenService(
            @Value("${bank.token.secreto}") String secreto,
            @Value("${bank.token.emisor}") String emisor,
            @Value("${bank.token.duracion}") Duration duracion) {
        return new TokenService(secreto, emisor, duracion);
    }

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http, TokenService tokens) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // El registro se protege con el token de dispositivo,
                        // que valida el propio controlador. No lleva JWT porque
                        // es justamente donde se obtiene.
                        .requestMatchers(HttpMethod.POST, "/api/movil/registro").permitAll()
                        .requestMatchers("/api/movil/**").hasRole("DISPOSITIVO")
                        .anyRequest().denyAll())
                .addFilterBefore(new FiltroJwt(tokens, CANAL), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
