package cl.duoc.bank.bff.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Credenciales de entrada y el token que se entrega a cambio. */
public class LoginWebDto {

    public record Solicitud(
            @NotBlank(message = "El usuario es obligatorio") String usuario,
            @NotBlank(message = "La clave es obligatoria") String clave) {
    }

    /**
     * El token y su vigencia.
     *
     * Se devuelven tambien los roles para que la interfaz sepa que menus dibujar
     * sin tener que decodificar el token. Es informativo: la autorizacion real la
     * hace el servidor con los roles que vienen FIRMADOS dentro del JWT, no con
     * este campo, que un cliente podria alterar sin consecuencia.
     */
    public record Respuesta(String token, long expiraEnSegundos, List<String> roles) {
    }
}
