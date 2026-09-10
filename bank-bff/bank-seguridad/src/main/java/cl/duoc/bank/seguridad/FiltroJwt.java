package cl.duoc.bank.seguridad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Valida el JWT de la cabecera Authorization y puebla el contexto de seguridad.
 *
 * Es el mismo filtro para los tres canales, pero cada BFF lo construye con SU
 * TokenService. Como cada TokenService tiene su propia clave y su propio emisor,
 * el mismo codigo rechaza en el cajero un token perfectamente valido para el
 * canal web. La logica es comun; la politica es de cada canal.
 *
 * El filtro no responde 401 por su cuenta: si el token no sirve, simplemente no
 * autentica y deja seguir la cadena. Quien decide que hacer con una peticion no
 * autenticada es la configuracion de autorizacion de cada BFF, que es donde esa
 * decision pertenece.
 */
@RequiredArgsConstructor
public class FiltroJwt extends OncePerRequestFilter {

    private static final String CABECERA = "Authorization";
    private static final String PREFIJO = "Bearer ";

    private final TokenService tokens;

    /** Canal que este filtro acepta. Un token de otro canal se descarta. */
    private final String canalEsperado;

    @Override
    protected void doFilterInternal(HttpServletRequest peticion,
                                    HttpServletResponse respuesta,
                                    FilterChain cadena) throws ServletException, IOException {

        String cabecera = peticion.getHeader(CABECERA);
        if (cabecera != null && cabecera.startsWith(PREFIJO)) {

            tokens.validar(cabecera.substring(PREFIJO.length()))
                    // Doble comprobacion del canal: la firma ya lo garantiza,
                    // pero verificar el claim deja el contrato explicito y
                    // protege de un error de configuracion en que dos canales
                    // terminaran compartiendo secreto por descuido.
                    .filter(id -> canalEsperado.equals(id.canal()))
                    .ifPresent(id -> SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    id.sujeto(),
                                    null,
                                    autoridades(id.roles()))));
        }
        cadena.doFilter(peticion, respuesta);
    }

    private static List<SimpleGrantedAuthority> autoridades(List<String> roles) {
        return roles.stream()
                .map(r -> new SimpleGrantedAuthority(r.startsWith("ROLE_") ? r : "ROLE_" + r))
                .toList();
    }
}
