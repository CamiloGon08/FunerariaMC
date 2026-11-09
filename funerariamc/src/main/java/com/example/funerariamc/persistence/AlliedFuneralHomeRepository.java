package com.example.funerariamc.persistence;

import com.example.funerariamc.model.AlliedFuneralHome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Funerarias aliadas de la red. */
public interface AlliedFuneralHomeRepository extends JpaRepository<AlliedFuneralHome, Long> {

    List<AlliedFuneralHome> findAllByOrderByNameAsc();

    /**
     * Otra funeraria con el mismo NIT, sin contar puntos, espacios ni guion
     * ("900.214.556-3" y "9002145563" son el mismo). exceptId es la que se está editando.
     */
    @Query("""
            select count(h) > 0 from AlliedFuneralHome h
            where replace(replace(replace(h.nit, '.', ''), ' ', ''), '-', '') = :digits
              and (:exceptId is null or h.id <> :exceptId)
            """)
    boolean existsNit(@Param("digits") String digits, @Param("exceptId") Long exceptId);
}
