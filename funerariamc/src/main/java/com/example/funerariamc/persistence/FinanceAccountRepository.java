package com.example.funerariamc.persistence;

import com.example.funerariamc.model.FinanceAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Cuentas de egresos que se eligen al registrar un movimiento. */
public interface FinanceAccountRepository extends JpaRepository<FinanceAccount, Long> {

    /** Cuentas activas ordenadas por nombre, para la lista del formulario. */
    List<FinanceAccount> findByActiveTrueOrderByName();
}
