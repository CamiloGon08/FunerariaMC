package com.example.funerariamc.persistence;

import org.springframework.data.jpa.repository.Query;
import com.example.funerariamc.model.Cremation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CremationRepository extends JpaRepository<Cremation, Long>{

    /** Cremacion de un difunto con su lote */
    @Query("""
        select c from Cremation c
        join fetch c.cremationBatch
        where c.deceased.Id = :deceasedID
        """)
    Optional<Cremation> findByDeceasedWithBatch(@Param("deceasedId") Long deceasedId);
}
