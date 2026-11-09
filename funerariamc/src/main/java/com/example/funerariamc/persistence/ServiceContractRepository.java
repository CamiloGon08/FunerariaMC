package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.FuneralPlan;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.enums.ContractStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ServiceContractRepository extends JpaRepository<ServiceContract, Long> {

    /** Contrato del servicio de un difunto. */
    Optional<ServiceContract> findByDeceasedId(Long deceasedId);

    /** Un difunto solo puede tener un contrato. */
    boolean existsByDeceasedId(Long deceasedId);

    /**
     * Velación de una sala en el tablero de Salas: solo las columnas que pinta la tarjeta.
     * Trae el difunto, el titular y el plan.
     */
    interface WakeRow {
        Long getContractId();
        String getContractNumber();
        Long getRoomId();
        LocalDateTime getWakeStartAt();
        LocalDateTime getWakeEndAt();
        LocalDateTime getPreparationCompletedAt();
        String getDeceasedName();
        String getDeceasedLastname();
        String getClientName();
        String getClientLastname();
        Long getPlanId();
        String getPlanName();
    }

    /** Velaciones que ocupan la sala en algún momento del día consultado. 
     * Los contratos cancelados no se muestran.*/
    @Query("""
            select c.id as contractId, c.contractNumber as contractNumber, w.id as roomId,
                   c.wakeStartAt as wakeStartAt, c.wakeEndAt as wakeEndAt,
                   c.preparationCompletedAt as preparationCompletedAt,
                   d.name as deceasedName, d.lastname as deceasedLastname,
                   cl.name as clientName, cl.lastname as clientLastname,
                   p.id as planId, p.planName as planName
            from ServiceContract c
            join c.wakeRoom w
            join c.deceased d
            join c.client cl
            left join c.funeralPlan p
            where c.status <> :cancelled
              and c.wakeStartAt is not null
              and c.wakeStartAt < :to
              and coalesce(c.wakeEndAt, c.wakeStartAt) >= :from
            order by c.wakeStartAt
            """)
    List<WakeRow> findWakes(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                            @Param("cancelled") ContractStatus cancelled);

    /**
     * Otra velación de la misma sala.
     * exceptId deja fuera al mismo contrato cuando se cambia de horario o de sala.
     */
    @Query("""
            select count(c) > 0 from ServiceContract c
            where c.wakeRoom.id = :roomId
              and c.id <> :exceptId
              and c.status <> :cancelled
              and c.wakeStartAt is not null
              and c.wakeStartAt < :end
              and coalesce(c.wakeEndAt, c.wakeStartAt) > :start
            """)
    boolean existsWakeOverlap(@Param("roomId") Long roomId, @Param("exceptId") Long exceptId,
                              @Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                              @Param("cancelled") ContractStatus cancelled);

    /** Contrato que ocupa la sala en ese horario, para decir cuál es en el aviso del cruce. */
    @Query("""
            select c.contractNumber from ServiceContract c
            where c.wakeRoom.id = :roomId
              and c.id <> :exceptId
              and c.status <> :cancelled
              and c.wakeStartAt is not null
              and c.wakeStartAt < :end
              and coalesce(c.wakeEndAt, c.wakeStartAt) > :start
            order by c.wakeStartAt
            """)
    List<String> findOverlappingNumbers(@Param("roomId") Long roomId, @Param("exceptId") Long exceptId,
                                        @Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                        @Param("cancelled") ContractStatus cancelled, Pageable pageable);

    /** Contratos activos sin sala, para el diálogo "Asignar a contrato" de Salas. */
    @Query("""
            select c from ServiceContract c
            join fetch c.deceased d
            join fetch c.client cl
            where c.status = :active
              and c.wakeRoom is null
              and (lower(c.contractNumber) like :pattern
                   or lower(concat(coalesce(d.name, ''), ' ', coalesce(d.lastname, ''))) like :pattern
                   or lower(concat(cl.name, ' ', coalesce(cl.lastname, ''))) like :pattern)
            order by c.id desc
            """)
    List<ServiceContract> searchWithoutRoom(@Param("active") ContractStatus active, @Param("pattern") String pattern,
                                            Pageable pageable);

    /** Contratos no cancelados que coinciden con el texto, para asociarles un pago a una funeraria aliada. */
    @Query("""
            select c from ServiceContract c
            join fetch c.deceased d
            join fetch c.client cl
            where c.status <> :cancelled
              and (lower(c.contractNumber) like :pattern
                   or lower(concat(coalesce(d.name, ''), ' ', coalesce(d.lastname, ''))) like :pattern
                   or lower(concat(cl.name, ' ', coalesce(cl.lastname, ''))) like :pattern)
            order by c.id desc
            """)
    List<ServiceContract> searchNotCancelled(@Param("cancelled") ContractStatus cancelled, @Param("pattern") String pattern,
                                             Pageable pageable);

    /** Contrato completo para el panel de la sala y el detalle de la venta. */
    @Query("""
            select c from ServiceContract c
            join fetch c.deceased
            join fetch c.client
            left join fetch c.wakeRoom
            left join fetch c.funeralPlan
            left join fetch c.funeralSalesperson
            left join fetch c.thanatopractor
            where c.id = :id
            """)
    Optional<ServiceContract> findDetailById(@Param("id") Long id);

    /** Último número de contrato del año (en "CT-2026-0148" devuelve 148), o 0 si no hay.
     * Si dos guardan a la vez, la columna única impide que se repita el número.*/
    @Query(value = """
            select coalesce(max(cast(substring(contract_number from '^CT-[0-9]{4}-([0-9]+)$') as integer)), 0)
            from service_contract
            where contract_number like :prefix
            """, nativeQuery = true)
    int findLastSequence(@Param("prefix") String prefix);

    /** Difuntos sin contrato vigente, para "Nuevo contrato". Un difunto con contrato
     * cancelado (venta anulada) vuelve a aparecer: ese contrato se reutiliza.*/
    @Query("""
            select d from Deceased d
            where not exists (select c.id from ServiceContract c where c.deceased = d and c.status <> :cancelled)
              and (lower(concat(coalesce(d.name, ''), ' ', coalesce(d.lastname, ''))) like :pattern
                   or lower(coalesce(d.documentNumber, '')) like :pattern)
            order by d.deathDate desc nulls last, d.id desc
            """)
    List<Deceased> searchDeceasedWithoutContract(@Param("cancelled") ContractStatus cancelled,
                                                 @Param("pattern") String pattern, Pageable pageable);

    /** Planes de un cliente (titular), para elegir el plan del contrato. */
    @Query("""
            select p from FuneralPlan p
            where p.client.id = :clientId
            order by p.startDate desc, p.id desc
            """)
    List<FuneralPlan> findPlansOfClient(@Param("clientId") Long clientId);

    /** Preparaciones del cuerpo por tanatólogo en un rango. */
    interface PreparationCount {
        Long getThanatopractorId();
        long getCases();
    }

    @Query("""
            select c.thanatopractor.id as thanatopractorId, count(c) as cases
            from ServiceContract c
            where c.thanatopractor is not null and c.status <> :cancelled
              and c.preparationCompletedAt >= :from and c.preparationCompletedAt < :to
            group by c.thanatopractor.id
            """)
    List<PreparationCount> countPreparations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                             @Param("cancelled") ContractStatus cancelled);
}
