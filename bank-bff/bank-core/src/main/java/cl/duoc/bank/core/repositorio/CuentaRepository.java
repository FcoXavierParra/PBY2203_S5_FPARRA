package cl.duoc.bank.core.repositorio;

import cl.duoc.bank.core.dominio.Cuenta;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CuentaRepository extends JpaRepository<Cuenta, Long> {
}
