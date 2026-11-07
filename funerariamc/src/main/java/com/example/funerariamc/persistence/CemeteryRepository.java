package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Cemetery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CemeteryRepository extends JpaRepository<Cemetery, Long> {

    /** Cementerios propios en el orden de las pestañas de la pantalla. */
    List<Cemetery> findAllByOrderByNameAsc();
}
