package com.example.funerariamc.persistence;

import com.example.funerariamc.model.CremationBatch;
import com.example.funerariamc.model.enums.CremationBatchStatus;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Lotes de cremación y las consultas de la pantalla de Cremaciones. */
public interface CremationBatchRepository extends JpaRepository<CremationBatch, Long> {

    /**
     * Una fila por difunto de cada lote. Trae los
     * lotes abiertos de cualquier fecha y los programados.
     */
    interface BoardRow {
        Long getBatchId();
        LocalDateTime getScheduledDate();
        String getFurnaceCode();
        CremationBatchStatus getStatus();
        Long getCremationId();
        Long getDeceasedId();
        String getName();
        String getLastname();
        String getDocumentNumber();
        DeathType getDeathType();
        Long getReleaseOrders();
        String getThanatopractorName();
        String getThanatopractorLastname();
        LocalDateTime getCompletedAt();
        String getCertificateNumber();
    }

    @Query("""
            select b.id as batchId, b.scheduledDate as scheduledDate, b.furnaceCode as furnaceCode,
                   b.status as status, c.id as cremationId, d.id as deceasedId, d.name as name,
                   d.lastname as lastname, d.documentNumber as documentNumber, d.deathType as deathType,
                   (select count(dc.id) from DeathCertificate dc
                     where dc.deceased = d and dc.documentType = :release) as releaseOrders,
                   t.name as thanatopractorName, t.lastname as thanatopractorLastname,
                   c.completedAt as completedAt, c.certificateNumber as certificateNumber
            from CremationBatch b
            left join Cremation c on c.cremationBatch = b
            left join c.deceased d
            left join c.thanatopractor t
            where b.status in :open or b.scheduledDate >= :from
            order by b.scheduledDate, b.id, c.id
            """)
    List<BoardRow> findBoardRows(@Param("open") Collection<CremationBatchStatus> open,
                                 @Param("from") LocalDateTime from,
                                 @Param("release") DocumentType release);

    /** Cremaciones terminadas en un periodo y en cuántos lotes. */
    interface FinishedCount {
        Long getCremations();
        Long getBatches();
    }

    @Query("""
            select count(c.id) as cremations, count(distinct c.cremationBatch.id) as batches
            from Cremation c
            where c.completedAt >= :from and c.completedAt < :to
            """)
    FinishedCount countFinished(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Lotes programados en un rango con cuántos difuntos llevan. */
    interface DayBatch {
        Long getBatchId();
        LocalDateTime getScheduledDate();
        String getFurnaceCode();
        CremationBatchStatus getStatus();
        long getCremations();
    }

    @Query("""
            select b.id as batchId, b.scheduledDate as scheduledDate, b.furnaceCode as furnaceCode,
                   b.status as status, count(c.id) as cremations
            from CremationBatch b
            left join Cremation c on c.cremationBatch = b
            where b.scheduledDate >= :from and b.scheduledDate < :to
            group by b.id, b.scheduledDate, b.furnaceCode, b.status
            order by b.scheduledDate, b.id
            """)
    List<DayBatch> findBatchesBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
