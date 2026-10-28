package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.model.enums.ExternalCause;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VehicleType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeceasedRepository extends JpaRepository<Deceased, Long> {

    /**
     * Difuntos que se pueden inhumar: no están en una bóveda, no fueron exhumados
     * y no tienen cremación. Busca por nombre, apellido o documento.
     */
    @Query("""
            select d from Deceased d
            where not exists (select v.id from Vault v where v.deceased = d)
              and not exists (select e.id from Exhumation e where e.deceased = d)
              and not exists (select c.id from Cremation c where c.deceased = d)
              and (lower(concat(d.name, ' ', d.lastname)) like :pattern
                   or lower(coalesce(d.documentNumber, '')) like :pattern)
            order by d.deathDate desc nulls last, d.id desc
            """)
    List<Deceased> searchWithoutVault(@Param("pattern") String pattern, Pageable pageable);

    /**
     * Una fila del registro de Difuntos (propuesta P-13): el difunto, su responsable, su plan,
     * su contrato de servicio, su destino (bóveda o cremación), el próximo traslado y cuántos
     * documentos de cada tipo tiene. Todo en una consulta para no hacer una por difunto.
     */
    interface DeceasedRow {
        Long getId();
        String getName();
        String getLastname();
        String getDocumentNumber();
        LocalDate getBirthDate();
        LocalDate getDeathDate();
        DeathType getDeathType();
        ExternalCause getExternalCause();
        String getResponsibleName();
        String getResponsibleLastname();
        Long getPlanId();
        String getPlanName();
        String getPlanHolderDocument();
        String getBeneficiaryRelationship();
        String getContractNumber();
        ContractStatus getContractStatus();
        LocalDateTime getPreparationCompletedAt();
        LocalDateTime getWakeStartAt();
        LocalDateTime getWakeEndAt();
        String getWakeRoom();
        String getVaultCode();
        Long getCremationId();
        LocalDateTime getCremationCompletedAt();
        LocalDateTime getNextTransportAt();
        long getTransportsInProgress();
        long getMedicalCertificates();
        long getForensicReports();
        long getReleaseOrders();
    }

    /** Columnas de una fila del registro; las comparten la lista y el expediente. */
    String REGISTRY_COLUMNS = """
            select d.id as id, d.name as name, d.lastname as lastname, d.documentNumber as documentNumber,
                   d.birthDate as birthDate, d.deathDate as deathDate, d.deathType as deathType,
                   d.externalCause as externalCause,
                   cl.name as responsibleName, cl.lastname as responsibleLastname,
                   p.id as planId, p.planName as planName, ph.documentNumber as planHolderDocument,
                   (select max(b.relationship) from Beneficiary b
                    where b.funeralPlan = p and b.documentNumber = d.documentNumber) as beneficiaryRelationship,
                   c.contractNumber as contractNumber, c.status as contractStatus,
                   c.preparationCompletedAt as preparationCompletedAt,
                   c.wakeStartAt as wakeStartAt, c.wakeEndAt as wakeEndAt, w.name as wakeRoom,
                   (select max(v.code) from Vault v where v.deceased = d) as vaultCode,
                   (select max(cr.id) from Cremation cr where cr.deceased = d) as cremationId,
                   (select max(cr.completedAt) from Cremation cr where cr.deceased = d) as cremationCompletedAt,
                   (select min(t.scheduledAt) from ServiceTransport t
                    where t.serviceContract = c and t.status in :pendingTransport) as nextTransportAt,
                   (select count(t) from ServiceTransport t
                    where t.serviceContract = c and t.status = :inProgress) as transportsInProgress,
                   (select count(dc) from DeathCertificate dc
                    where dc.deceased = d and dc.documentType = :medical) as medicalCertificates,
                   (select count(dc) from DeathCertificate dc
                    where dc.deceased = d and dc.documentType = :forensic) as forensicReports,
                   (select count(dc) from DeathCertificate dc
                    where dc.deceased = d and dc.documentType = :release) as releaseOrders
            """;

    /** Tablas de una fila del registro. */
    String REGISTRY_FROM = """
            from Deceased d
            left join d.client cl
            left join d.funeralPlan p
            left join p.client ph
            left join ServiceContract c on c.deceased = d
            left join c.wakeRoom w
            """;

    @Query(REGISTRY_COLUMNS + REGISTRY_FROM + "order by d.deathDate desc nulls last, d.id desc")
    List<DeceasedRow> findRegistry(@Param("pendingTransport") Collection<TransportStatus> pendingTransport,
                                   @Param("inProgress") TransportStatus inProgress,
                                   @Param("medical") DocumentType medical,
                                   @Param("forensic") DocumentType forensic,
                                   @Param("release") DocumentType release);

    /**
     * La fila de un difunto con lo que el expediente agrega al recorrido (tanatopractor,
     * cementerio, horno). Una sola consulta en vez de una por contrato, bóveda y cremación.
     */
    interface DetailRow extends DeceasedRow {
        Long getContractId();
        String getThanatopractorName();
        String getThanatopractorLastname();
        String getCemeteryName();
        LocalDate getVaultLeaseStart();
        String getFurnaceCode();
        LocalDateTime getCremationScheduledAt();
    }

    @Query(REGISTRY_COLUMNS + """
            , c.id as contractId, th.name as thanatopractorName, th.lastname as thanatopractorLastname,
            cem.name as cemeteryName, vd.leaseStartDate as vaultLeaseStart,
            cb.furnaceCode as furnaceCode, cb.scheduledDate as cremationScheduledAt
            """ + REGISTRY_FROM + """
            left join c.thanatopractor th
            left join Vault vd on vd.deceased = d
            left join vd.cemetery cem
            left join Cremation crd on crd.deceased = d
            left join crd.cremationBatch cb
            where d.id = :id
            """)
    Optional<DetailRow> findDetailRow(@Param("id") Long id,
                                      @Param("pendingTransport") Collection<TransportStatus> pendingTransport,
                                      @Param("inProgress") TransportStatus inProgress,
                                      @Param("medical") DocumentType medical,
                                      @Param("forensic") DocumentType forensic,
                                      @Param("release") DocumentType release);

    /** Traslado programado o en ruta de un día, para "Salidas de hoy". */
    interface DepartureRow {
        Long getDeceasedId();
        String getName();
        String getLastname();
        LocalDateTime getScheduledAt();
        TransportStatus getStatus();
        String getPlate();
        VehicleType getVehicleType();
        String getVaultCode();
        Long getCremationId();
    }

    @Query("""
            select d.id as deceasedId, d.name as name, d.lastname as lastname, t.scheduledAt as scheduledAt,
                   t.status as status, v.plate as plate, v.vehicleType as vehicleType,
                   (select max(va.code) from Vault va where va.deceased = d) as vaultCode,
                   (select max(cr.id) from Cremation cr where cr.deceased = d) as cremationId
            from ServiceTransport t join t.serviceContract c join c.deceased d join t.vehicle v
            where t.scheduledAt >= :from and t.scheduledAt < :to and t.status <> :cancelled
            order by t.scheduledAt
            """)
    List<DepartureRow> findDepartures(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                      @Param("cancelled") TransportStatus cancelled);

    /** Un difunto con su responsable y su plan (con el titular), para el expediente. */
    @Query("""
            select d from Deceased d
            left join fetch d.client
            left join fetch d.funeralPlan p
            left join fetch p.client
            where d.id = :id
            """)
    Optional<Deceased> findDetailById(@Param("id") Long id);

    /** El documento de un difunto no se repite (los NN no tienen documento). */
    boolean existsByDocumentNumber(String documentNumber);

    /** Cuántos difuntos tiene vinculado un plan exequial (indicador "Con plan exequial"). */
    long countByFuneralPlanIsNotNull();
}
