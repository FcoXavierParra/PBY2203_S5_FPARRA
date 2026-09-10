package cl.duoc.bank.bff.movil.dto;

import jakarta.validation.constraints.NotBlank;

/** Registro del dispositivo y el token de sesion que recibe. */
public class RegistroMovilDto {

    public record Solicitud(
            @NotBlank(message = "El identificador del dispositivo es obligatorio")
            String deviceId) {
    }

    public record Respuesta(String token, long expiraEnSegundos) {
    }
}
