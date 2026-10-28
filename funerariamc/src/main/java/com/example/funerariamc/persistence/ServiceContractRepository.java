package com.example.funerariamc.persistence;

import com.example.funerariamc.model.ServiceContract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceContractRepository extends JpaRepository<ServiceContract, Long> {

    /** Contrato del servicio de un difunto. */
    Optional<ServiceContract> findByDeceasedId(Long deceasedId);
}

