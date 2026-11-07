package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Exhumation;
import com.example.funerariamc.model.enums.ExhumationDestination;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ExhumationRepository extends JpaRepository<Exhumation, Long> {

    /** Fila de una exhumación.*/
    interface ExhumationRow {
        Long getVaultId();

        String getVaultCode();

        String getDeceasedName();

        String getDeceasedLastname();

        LocalDate getExhumationDate();

        ExhumationDestination getDestination();
    }

    /** Historial de exhumaciones de una bóveda */
    @Query("""
            select v.id as vaultId, v.code as vaultCode, d.name as deceasedName, d.lastname as deceasedLastname,
                   e.exhumationDate as exhumationDate, e.destination as destination
            from Exhumation e
            join e.vault v
            join e.deceased d
            where v.id = :vaultId
            order by e.exhumationDate desc, e.id desc
            """)
    List<ExhumationRow> findHistoryRows(@Param("vaultId") Long vaultId);

    /** Últimas exhumaciones de un cementerio */
    @Query("""
            select v.id as vaultId, v.code as vaultCode, d.name as deceasedName, d.lastname as deceasedLastname,
                   e.exhumationDate as exhumationDate, e.destination as destination
            from Exhumation e
            join e.vault v
            join e.deceased d
            where v.cemetery.id = :cemeteryId
            order by e.exhumationDate desc, e.id desc
            """)
    List<ExhumationRow> findRecentRows(@Param("cemeteryId") Long cemeteryId, Pageable pageable);
}
