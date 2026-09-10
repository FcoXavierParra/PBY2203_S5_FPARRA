package cl.duoc.bank.bff.web;

import cl.duoc.bank.core.CoreConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * BFF del canal WEB. Puerto 8081.
 *
 * Sirve a un navegador de escritorio: pantalla grande, ancho de banda holgado e
 * interfaces que muestran mucho a la vez. Sus respuestas son las mas completas
 * de los tres canales, con paginacion y desgloses.
 */
@SpringBootApplication
@Import(CoreConfig.class)
public class BffWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffWebApplication.class, args);
    }
}
