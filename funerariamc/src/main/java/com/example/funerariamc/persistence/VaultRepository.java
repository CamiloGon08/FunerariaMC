package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Vault;
import com.example.funerariamc.utils.enums.VaultStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface VaultRepository extends JpaRepository<Vault, Long> {

    /**Una bóveda del muro con solo lo que se pinta: código, estado, fechas, difunto y titular.*/
    interface WallRow {
        Long getId();
        String getCode();
        VaultStatus getStatus();
        LocalDate getLeaseStartDate();
        LocalDate getLeaseEndDate();
        String getDeceasedName();
        String getDeceasedLastname();
        String getClientName();
        String getClientLastname();
    }

    /** Panel de detalle: la bóveda, su difunto, su titular y el número de contrato en una sola consulta. */
    interface DetailRow extends WallRow {
        String getCemeteryName();
        Long getDeceasedId();
        LocalDate getDeceasedBirthDate();
        LocalDate getDeceasedDeathDate();
        Long getClientId();
        String getClientDocumentType();
        String getClientDocumentNumber();
        String getClientPhone();
        String getClientEmail();
        String getContractNumber();
    }

    /** Todas las bóvedas de un cementerio, para dibujar el muro con una sola consulta. */
    @Query("""
            select v.id as id, v.code as code, v.status as status,
                   v.leaseStartDate as leaseStartDate, v.leaseEndDate as leaseEndDate,
                   d.name as deceasedName, d.lastname as deceasedLastname,
                   c.name as clientName, c.lastname as clientLastname
            from Vault v
            left join v.deceased d
            left join v.client c
            where v.cemetery.id = :cemeteryId
            order by v.code
            """)
    List<WallRow> findWallRows(@Param("cemeteryId") Long cemeteryId);

    /** Datos del panel de una bóveda, con el contrato del difunto. */
    @Query("""
            select v.id as id, v.code as code, v.status as status,
                   v.leaseStartDate as leaseStartDate, v.leaseEndDate as leaseEndDate,
                   cem.name as cemeteryName,
                   d.id as deceasedId, d.name as deceasedName, d.lastname as deceasedLastname,
                   d.birthDate as deceasedBirthDate, d.deathDate as deceasedDeathDate,
                   c.id as clientId, c.name as clientName, c.lastname as clientLastname,
                   c.documentType as clientDocumentType, c.documentNumber as clientDocumentNumber,
                   c.phone as clientPhone, c.email as clientEmail,
                   sc.contractNumber as contractNumber
            from Vault v
            join v.cemetery cem
            left join v.deceased d
            left join v.client c
            left join ServiceContract sc on sc.deceased = d
            where v.id = :id
            """)
    Optional<DetailRow> findDetailRow(@Param("id") Long id);

    /** Una bóveda con su cementerio, titular y difunto, para asignar, renovar o exhumar. */
    @Query("""
            select v from Vault v
            join fetch v.cemetery
            left join fetch v.client
            left join fetch v.deceased
            where v.id = :id
            """)
    Optional<Vault> findDetailById(@Param("id") Long id);

    /** Bóveda donde reposa un difunto, con su cementerio. */
    @Query("""
            select v from Vault v
            join fetch v.cemetery
            where v.deceased.id = :deceasedId
            """)
    Optional<Vault> findByDeceasedWithCemetery(@Param("deceasedId") Long deceasedId);

    /** Un difunto reposa en una sola bóveda a la vez. */
    boolean existsByDeceasedId(Long deceasedId);

    /**Códigos de las bóvedas de un cementerio, para "Agregar bóvedas".*/
    @Query("select v.code from Vault v where v.cemetery.id = :cemeteryId")
    List<String> findCodesByCemeteryId(@Param("cemeteryId") Long cemeteryId);

    /**
     * Registra muchas bóvedas de un cementerio en una sola sentencia (un solo viaje a la base de datos).
     * Guardarlas una por una tomaba un viaje por bóveda: con un pabellón de 150 bóvedas, varios segundos.
     * {@code codes} son los códigos separados por coma, por ejemplo "A1-01,A1-02".
     */
    @Modifying
    @Query(value = """
            insert into vault (cemetery_id, code, status)
            select :cemeteryId, code, :status
            from unnest(string_to_array(:codes, ',')) as code
            """, nativeQuery = true)
    int insertCodes(@Param("cemeteryId") Long cemeteryId, @Param("codes") String codes, @Param("status") String status);

    /** Cuántas bóvedas tiene un cementerio (mensaje de "Borrar cementerio"). */
    long countByCemeteryId(Long cemeteryId);

    /**
     * Cuántas bóvedas del cementerio ya se usaron: no están disponibles o guardan titular,
     * difunto o fechas de arriendo. Un cementerio con alguna así no se puede borrar.
     */
    @Query("""
            select count(v) from Vault v
            where v.cemetery.id = :cemeteryId
              and (v.status <> com.example.funerariamc.utils.enums.VaultStatus.AVAILABLE
                   or v.client is not null or v.deceased is not null
                   or v.leaseStartDate is not null or v.leaseEndDate is not null)
            """)
    long countUsedByCemeteryId(@Param("cemeteryId") Long cemeteryId);

    /** Borra todas las bóvedas de un cementerio en una sola sentencia (al borrar un cementerio sin uso). */
    @Modifying
    @Query("delete from Vault v where v.cemetery.id = :cemeteryId")
    int deleteByCemeteryId(@Param("cemeteryId") Long cemeteryId);

    /** Bóvedas por cementerio y estado, para la ocupación del Panel de análisis. */
    interface CemeteryCount {
        Long getCemeteryId();
        String getCemeteryName();
        VaultStatus getStatus();
        long getVaults();
    }

    @Query("""
            select c.id as cemeteryId, c.name as cemeteryName, v.status as status, count(v) as vaults
            from Vault v join v.cemetery c
            group by c.id, c.name, v.status
            order by c.name
            """)
    List<CemeteryCount> countByCemeteryAndStatus();

    /** Bóvedas en un estado (por ejemplo, en mora) con su cementerio, la de arriendo más viejo primero. */
    @Query("""
            select v from Vault v join fetch v.cemetery
            where v.status = :status
            order by v.leaseEndDate, v.id
            """)
    List<Vault> findByStatusWithCemetery(@Param("status") VaultStatus status, Pageable pageable);

    long countByStatus(VaultStatus status);

    /** Arriendos próximos a vencer, con el titular y el cementerio para el recordatorio. */
    @Query("""
            select v from Vault v
            join fetch v.client
            join fetch v.cemetery
            where v.leaseEndDate between :from and :to
              and v.status <> :excludedStatus
            order by v.leaseEndDate, v.id
            """)
    List<Vault> findLeasesEndingBetween(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                       @Param("excludedStatus") VaultStatus excludedStatus);
}
