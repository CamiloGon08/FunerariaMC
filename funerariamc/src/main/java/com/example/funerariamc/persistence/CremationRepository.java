package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Cremation;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.model.enums.ExhumationDestination;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Cremaciones de difuntos dentro de un lote. */
public interface CremationRepository extends JpaRepository<Cremation, Long> {

    /** Cremación de un difunto con su lote. */
    @Query("""
        select c from Cremation c
        join fetch c.cremationBatch
        where c.deceased.id = :deceasedId
        """)
    Optional<Cremation> findByDeceasedWithBatch(@Param("deceasedId") Long deceasedId);

    /** Cremación con su lote y su difunto, para registrar el certificado o quitarla del lote. */
    @Query("""
        select c from Cremation c
        join fetch c.cremationBatch
        join fetch c.deceased
        where c.id = :id
        """)
    Optional<Cremation> findDetailById(@Param("id") Long id);

    /** Un difunto solo puede tener una cremación. */
    boolean existsByDeceasedId(Long deceasedId);

    /** Difuntos del lote con su tipo de muerte, para iniciarlo. */
    @Query("""
        select c from Cremation c
        join fetch c.deceased
        where c.cremationBatch.id = :batchId
        order by c.id
        """)
    List<Cremation> findByBatchWithDeceased(@Param("batchId") Long batchId);

    /** Cremaciones del lote que todavía no tienen certificado. */
    long countByCremationBatchIdAndCompletedAtIsNull(Long batchId);

    /** Órdenes de entrega de la Fiscalía registradas para un difunto. */
    @Query("""
        select count(dc.id) from DeathCertificate dc
        where dc.deceased.id = :deceasedId and dc.documentType = :release
        """)
    long countReleaseOrders(@Param("deceasedId") Long deceasedId, @Param("release") DocumentType release);

    /** true si el difunto está en una bóveda (primero se registra su exhumación). */
    @Query("select count(v.id) > 0 from Vault v where v.deceased.id = :deceasedId")
    boolean isInVault(@Param("deceasedId") Long deceasedId);

    /** true si el difunto tiene una exhumación con otro destino (fosa común, osario o entrega a la familia). */
    @Query("""
        select count(e.id) > 0 from Exhumation e
        where e.deceased.id = :deceasedId and e.destination <> :cremation
        """)
    boolean hasOtherDestination(@Param("deceasedId") Long deceasedId,
                                @Param("cremation") ExhumationDestination cremation);

    /**
     * Opción del buscador "Agregar difunto al lote": el difunto, cuántas órdenes de entrega tiene
     * y, si tiene contrato activo, si la preparación del cuerpo ya está registrada.
     */
    interface CandidateRow {
        Long getId();
        String getName();
        String getLastname();
        String getDocumentNumber();
        DeathType getDeathType();
        Long getReleaseOrders();
        Long getContractId();
        String getContractNumber();
        Long getThanatopractorId();
        String getThanatopractorName();
        String getThanatopractorLastname();
        LocalDateTime getPreparationCompletedAt();
    }

    /**
     * Difuntos que pueden ir a un lote: sin cremación, fuera de bóveda y sin una exhumación con
     * otro destino. Busca por nombre, apellido o documento; los más recientes primero.
     */
    @Query("""
        select d.id as id, d.name as name, d.lastname as lastname, d.documentNumber as documentNumber,
               d.deathType as deathType,
               (select count(dc.id) from DeathCertificate dc
                 where dc.deceased = d and dc.documentType = :release) as releaseOrders,
               sc.id as contractId, sc.contractNumber as contractNumber,
               t.id as thanatopractorId, t.name as thanatopractorName, t.lastname as thanatopractorLastname,
               sc.preparationCompletedAt as preparationCompletedAt
        from Deceased d
        left join ServiceContract sc on sc.deceased = d and sc.status = :active
        left join sc.thanatopractor t
        where not exists (select c.id from Cremation c where c.deceased = d)
          and not exists (select v.id from Vault v where v.deceased = d)
          and not exists (select e.id from Exhumation e where e.deceased = d and e.destination <> :cremation)
          and (lower(concat(d.name, ' ', d.lastname)) like :pattern
               or lower(coalesce(d.documentNumber, '')) like :pattern)
        order by d.deathDate desc nulls last, d.id desc
        """)
    List<CandidateRow> searchCandidates(@Param("pattern") String pattern,
                                        @Param("release") DocumentType release,
                                        @Param("active") ContractStatus active,
                                        @Param("cremation") ExhumationDestination cremation,
                                        Pageable pageable);
}
