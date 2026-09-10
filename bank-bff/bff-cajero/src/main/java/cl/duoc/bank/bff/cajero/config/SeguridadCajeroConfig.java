package cl.duoc.bank.bff.cajero.config;

import cl.duoc.bank.seguridad.FiltroJwt;
import cl.duoc.bank.seguridad.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

/**
 * Seguridad del canal CAJERO: tres controles encadenados.
 *
 *   1. FiltroTerminal   el EQUIPO se identifica con su clave. Un cajero
 *                       desconocido no llega siquiera a poder intentar un PIN.
 *   2. FiltroJwt        el CLIENTE se identifica con el token que recibio al
 *                       insertar tarjeta y digitar PIN.
 *   3. FiltroRevocacion el token ademas tiene que seguir vigente en el registro
 *                       de sesiones.
 *
 * POR QUE EL TERCERO, SI EL SEGUNDO YA VALIDA EL TOKEN
 * ----------------------------------------------------
 * Porque un JWT no se puede invalidar antes de que expire, y este canal lo
 * necesita: la sesion muere cuando el dispensador entrega los billetes, no dos
 * minutos despues. Ver el javadoc de SesionCajeroService.
 *
 * COMO SE COMPARA CON LOS OTROS DOS CANALES
 * -----------------------------------------
 * Es el esquema mas estricto de los tres, porque es el unico que entrega dinero
 * fisico desde un equipo que esta en la calle. El canal web pide credenciales
 * una vez y da un token de ocho horas; el movil registra el aparato y da uno de
 * treinta minutos; aqui son dos factores encadenados, dos minutos y revocacion
 * inmediata. Los tres resuelven autenticacion y autorizacion, y ninguno lo hace
 * igual porque las amenazas de cada canal son distintas.
 *
 * ALCANCE: la clave del terminal esta en configuracion. En produccion un cajero
 * se autentica con certificado de cliente sobre TLS mutuo, que es bastante mas
 * que una cabecera.
 */
@Slf4j
@Configuration
public class SeguridadCajeroConfig {

    /** Nombre del canal. Viaja en el token y el filtro lo exige. */
    public static final String CANAL = "cajero";

    @Bean
    public TokenService tokenService(
            @Value("${bank.token.secreto}") String secreto,
            @Value("${bank.token.emisor}") String emisor,
            @Value("${bank.token.duracion}") Duration duracion) {
        return new TokenService(secreto, emisor, duracion);
    }

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http,
                                      TokenService tokens,
                                      SesionCajeroService sesiones,
                                      FiltroTerminal filtroTerminal) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Abrir sesion exige terminal valido, pero todavia no token.
                        .requestMatchers(HttpMethod.POST, "/api/cajero/sesion").hasRole("TERMINAL")
                        // El resto exige ademas la sesion del cliente, vigente.
                        .requestMatchers("/api/cajero/**").hasRole("SESION")
                        .anyRequest().denyAll())
                .addFilterBefore(filtroTerminal, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new FiltroJwt(tokens, CANAL), FiltroTerminal.class)
                .addFilterAfter(new FiltroRevocacion(sesiones), FiltroJwt.class)
                .build();
    }

    @Bean
    public FiltroTerminal filtroTerminal(@Value("${bank.cajero.clave-terminal:atm-key-demo-2026}") String clave) {
        return new FiltroTerminal(clave);
    }

    /** Primer factor: identifica al equipo. */
    public static class FiltroTerminal extends OncePerRequestFilter {

        private final byte[] clave;

        public FiltroTerminal(String clave) {
            this.clave = clave.getBytes();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest peticion,
                                        HttpServletResponse respuesta,
                                        FilterChain cadena) throws ServletException, IOException {

            String recibida = peticion.getHeader("X-ATM-Terminal");
            if (recibida != null && MessageDigest.isEqual(recibida.getBytes(), clave)) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "terminal", null,
                                List.of(new SimpleGrantedAuthority("ROLE_TERMINAL"))));
            }
            cadena.doFilter(peticion, respuesta);
        }
    }

    /**
     * Tercer control: el token, ademas de valido, debe seguir vigente.
     *
     * Corre despues de FiltroJwt y hace dos cosas. Si el token fue revocado,
     * BORRA la autenticacion que aquel dejo puesta, de modo que la peticion
     * llegue como anonima. Si sigue vigente, ELEVA la autenticacion agregando
     * el rol SESION y conservando el rol TERMINAL del primer filtro, que
     * FiltroJwt habia sobrescrito.
     */
    @RequiredArgsConstructor
    public static class FiltroRevocacion extends OncePerRequestFilter {

        private final SesionCajeroService sesiones;

        @Override
        protected void doFilterInternal(HttpServletRequest peticion,
                                        HttpServletResponse respuesta,
                                        FilterChain cadena) throws ServletException, IOException {

            var contexto = SecurityContextHolder.getContext();
            var autenticacion = contexto.getAuthentication();
            String cabecera = peticion.getHeader("Authorization");

            if (autenticacion != null && cabecera != null && cabecera.startsWith("Bearer ")) {

                String token = cabecera.substring("Bearer ".length());

                // El sujeto del token es el numero de cuenta. Que NO sea un
                // numero significa que FiltroJwt no llego a autenticar -token
                // corrupto, expirado o de otro canal- y lo que sigue en el
                // contexto es la identidad del terminal, no la del cliente.
                Long cuentaId = numeroDeCuenta(autenticacion.getName());

                if (cuentaId != null && sesiones.estaVigente(token)) {
                    contexto.setAuthentication(new UsernamePasswordAuthenticationToken(
                            cuentaId,
                            token,
                            List.of(new SimpleGrantedAuthority("ROLE_TERMINAL"),
                                    new SimpleGrantedAuthority("ROLE_SESION"))));
                } else if (cuentaId != null) {
                    log.debug("Token con firma valida pero sesion revocada o vencida");
                    contexto.setAuthentication(null);
                }
                // Si cuentaId es null se deja el contexto como estaba: la
                // peticion sigue con el rol TERMINAL y la autorizacion la
                // rechazara por faltarle SESION.
            }
            cadena.doFilter(peticion, respuesta);
        }

        /** Devuelve el numero de cuenta, o null si el sujeto no es uno. */
        private static Long numeroDeCuenta(String sujeto) {
            try {
                return Long.valueOf(sujeto);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
