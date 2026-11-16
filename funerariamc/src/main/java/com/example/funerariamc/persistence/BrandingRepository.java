package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Branding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;


import java.util.Optional;
public interface BrandingRepository extends JpaRepository<Branding, Short> {

    /**Unica fila de la marca con quien la cambio por ultima vez */
    @Query("""
        select b from Branding b 
        left join fetch b.updateBy u 
        left join fetch u.employee
        where b.id = 1    
        """) 
    Optional<Branding> findCurrent();
}
