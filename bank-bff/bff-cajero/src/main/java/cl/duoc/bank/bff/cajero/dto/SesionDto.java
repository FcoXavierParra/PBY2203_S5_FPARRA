package cl.duoc.bank.bff.cajero.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Peticiones y respuestas del inicio de sesion en el terminal. */
public class SesionDto {

    /** Lo que envia el cajero cuando el cliente inserta la tarjeta y digita su PIN. */
    public record Solicitud(
            @NotNull(message = "La tarjeta es obligatoria")
            Long cuentaId,

            @NotBlank(message = "El PIN es obligatorio")
            @Pattern(regexp = "[0-9]{4}", message = "El PIN son 4 digitos")
            String pin) {
    }

    /**
     * Token de sesion efimero: un JWT firmado por este canal.
     *
     * Dura dos minutos. Una sesion de cajero es una persona parada frente a una
     * maquina en la calle: si se va sin cerrar, la ventana en que alguien podria
     * continuar su sesion tiene que ser lo mas corta posible. En el canal web la
     * credencial vale indefinidamente y en el movil el token del dispositivo
     * tambien; aqui no, y esa diferencia es del canal, no del banco.
     */
    public record Respuesta(String token, long expiraEnSegundos) {
    }
}
