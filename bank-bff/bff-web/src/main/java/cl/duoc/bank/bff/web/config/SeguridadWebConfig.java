package cl.duoc.bank.bff.web.config;

import cl.duoc.bank.seguridad.FiltroJwt;
import cl.duoc.bank.seguridad.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Duration;

/**
 * Seguridad del canal WEB: credenciales una vez, token despues.
 *
 * QUE CAMBIO RESPECTO DE LA SEMANA ANTERIOR
 * -----------------------------------------
 * Antes cada peticion viajaba con HTTP Basic, o sea con la contrasena del
 * usuario repetida una y otra vez. Ahora se entrega una sola vez en /login y a
 * cambio se recibe un JWT firmado. La diferencia practica es que el navegador
 * ya no necesita guardar la contrasena para poder reenviarla, y que un token
 * interceptado caduca solo.
 *
 * POR QUE ESTE CANAL TIENE ROLES Y LOS OTROS NO
 * ---------------------------------------------
 * Aca quien se autentica es una PERSONA con nombre de usuario, asi que tiene
 * sentido distinguir que puede ver cada quien: un cliente entra a su ficha, un
 * ejecutivo recorre ademas la cartera completa. En el movil la identidad es el
 * aparato y en el cajero la tarjeta; ninguno de los dos tiene un equivalente de
 * "ejecutivo".
 *
 * ALCANCE: los usuarios estan en memoria y el secreto de firma llega por
 * configuracion con un valor por defecto de desarrollo. En produccion la
 * identidad vendria de OAuth2/OIDC contra el directorio corporativo y el
 * secreto de un gestor de secretos.
 */
@Configuration
public class SeguridadWebConfig {

    /** Nombre del canal. Viaja en el token y el filtro lo exige. */
    public static final String CANAL = "web";

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
                // Sin CSRF y sin sesion de servidor: la credencial de cada
                // peticion es el token, que el cliente envia explicitamente. Un
                // ataque CSRF se apoya en credenciales que el navegador adjunta
                // solo, como una cookie de sesion, y aqui no hay ninguna.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Unico endpoint abierto: el que entrega el token.
                        .requestMatchers(HttpMethod.POST, "/api/web/login").permitAll()
                        // Recorrer la cartera completa es atribucion de ejecutivo.
                        .requestMatchers(HttpMethod.GET, "/api/web/cuentas").hasRole("EJECUTIVO")
                        .requestMatchers("/api/web/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(new FiltroJwt(tokens, CANAL), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public UserDetailsService usuarios(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("cliente")
                        .password(encoder.encode("cliente123"))
                        .roles("CLIENTE")
                        .build(),
                User.withUsername("ejecutivo")
                        .password(encoder.encode("ejecutivo123"))
                        .roles("EJECUTIVO", "CLIENTE")
                        .build());
    }

    @Bean
    public PasswordEncoder encoder() {
        return new BCryptPasswordEncoder();
    }
}
