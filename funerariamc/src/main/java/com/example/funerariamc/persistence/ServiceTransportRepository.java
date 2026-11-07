package com.example.funerariamc.persistence;

import com.example.funerariamc.model.ServiceTransport;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.StopType;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VehicleType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

// Traslados de un contrato de servicio. 
public interface ServiceTransportRepository extends JpaRepository<ServiceTransport, Long>{
    /** Traslado con su vehículo, conductor y el lugar de destino*/
    interface TransportRow{
        ServiceTransport getTransport();
        String getDestination();
    }

    @Query("""
            select t as transport,
                   (select max(s.placeName) from TransportStop s
                    where s.serviceTransport = t and s.stopType = :destination) as destination
            from ServiceTransport t
            join fetch t.vehicle
            join fetch t.driver
            where t.serviceContract.id = :contractId
            order by t.scheduledAt
            """)
    List<TransportRow> findByContract(@Param("contractId") Long contractId,
                                      @Param("destination") StopType destination);

    /** Destino del traslado de salida de cada contrato. */
    interface DestinationRow {
        Long getContractId();
        String getDestination();
        LocalDateTime getScheduledAt();
    }

    @Query("""
            select t.serviceContract.id as contractId, s.placeName as destination, t.scheduledAt as scheduledAt
            from TransportStop s
            join s.serviceTransport t
            where t.serviceContract.id in :contractIds and s.stopType = :destination
            order by t.scheduledAt, s.stopOrder
            """)
    List<DestinationRow> findDestinations(@Param("contractIds") Collection<Long> contractIds,
                                          @Param("destination") StopType destination);


    // ---------- módulo Transporte ----------

    /** Traslado de la pestaña Traslados y del monitor, con su contrato, vehículo y conductor. */
    interface TransferRow {
        Long getId();
        Long getContractId();
        String getContractNumber();
        String getDeceasedName();
        String getDeceasedLastname();
        Long getVehicleId();
        String getPlate();
        VehicleType getVehicleType();
        Long getDriverId();
        String getDriverName();
        String getDriverLastname();
        LocalDateTime getScheduledAt();
        Boolean getIncluded();
        TransportStatus getStatus();
        LocalDateTime getStartedAt();
        LocalDateTime getCompletedAt();
        String getNotes();
    }

    /**
     * Traslados programados, más los que siguen en ruta aunque hayan salido antes.
     */
    @Query("""
            select t.id as id, c.id as contractId, c.contractNumber as contractNumber,
                   d.name as deceasedName, d.lastname as deceasedLastname,
                   v.id as vehicleId, v.plate as plate, v.vehicleType as vehicleType,
                   dr.id as driverId, dr.name as driverName, dr.lastname as driverLastname,
                   t.scheduledAt as scheduledAt, t.included as included, t.status as status,
                   t.startedAt as startedAt, t.completedAt as completedAt, t.notes as notes
            from ServiceTransport t
            join t.serviceContract c
            join c.deceased d
            join t.vehicle v
            join t.driver dr
            where t.status <> :cancelled
              and ((t.scheduledAt >= :from and t.scheduledAt < :to) or t.status = :inProgress)
            order by t.scheduledAt, c.contractNumber, v.vehicleType desc, v.plate
            """)
    List<TransferRow> findTransfers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                    @Param("cancelled") TransportStatus cancelled,
                                    @Param("inProgress") TransportStatus inProgress);

    /**Traslados vigentes.*/
    interface BusyRow {
        Long getId();
        Long getVehicleId();
        String getPlate();
        Long getDriverId();
        String getDriverName();
        String getDriverLastname();
        String getContractNumber();
        LocalDateTime getScheduledAt();
        LocalDateTime getLastPlannedAt();
    }

    @Query("""
            select t.id as id, t.vehicle.id as vehicleId, t.vehicle.plate as plate,
                   t.driver.id as driverId, t.driver.name as driverName, t.driver.lastname as driverLastname,
                   t.serviceContract.contractNumber as contractNumber, t.scheduledAt as scheduledAt,
                   (select max(s.plannedAt) from TransportStop s where s.serviceTransport = t) as lastPlannedAt
            from ServiceTransport t
            where t.status in :statuses
              and t.scheduledAt <= :to
              and (t.vehicle.id in :vehicleIds or t.driver.id in :driverIds)
            """)
    List<BusyRow> findBusy(@Param("vehicleIds") Collection<Long> vehicleIds,
                           @Param("driverIds") Collection<Long> driverIds,
                           @Param("statuses") Collection<TransportStatus> statuses,
                           @Param("to") LocalDateTime to);

    /** Contrato vigente. */
    interface ContractOption {
        Long getId();
        String getContractNumber();
        String getDeceasedName();
        String getDeceasedLastname();
        LocalDateTime getWakeEndAt();
    }

    /** Busca por número de contrato o por nombre del difunto.*/    @Query("""
            select c.id as id, c.contractNumber as contractNumber, d.name as deceasedName,
                   d.lastname as deceasedLastname, c.wakeEndAt as wakeEndAt
            from ServiceContract c
            join c.deceased d
            where c.status = :status
              and (:text = '' or lower(c.contractNumber) like concat('%', :text, '%')
                   or lower(concat(d.name, ' ', coalesce(d.lastname, ''))) like concat('%', :text, '%'))
            order by c.id desc
            """)
    List<ContractOption> searchContracts(@Param("text") String text, @Param("status") ContractStatus status,
                                         Pageable pageable);

    /** Cuántos vehículos incluidos de cada tipo tiene ya el contrato. */
    interface IncludedCount {
        VehicleType getVehicleType();
        long getTotal();
    }

    @Query("""
            select t.vehicle.vehicleType as vehicleType, count(t) as total
            from ServiceTransport t
            where t.serviceContract.id = :contractId and t.included = true and t.status <> :cancelled
            group by t.vehicle.vehicleType
            """)
    List<IncludedCount> countIncluded(@Param("contractId") Long contractId,
                                      @Param("cancelled") TransportStatus cancelled);

    /** Buses adicionales ya asignados al contrato. */
    @Query("""
            select count(t) from ServiceTransport t
            where t.serviceContract.id = :contractId and t.included = false and t.status <> :cancelled
            """)
    long countAdditional(@Param("contractId") Long contractId, @Param("cancelled") TransportStatus cancelled);

    /** Buses adicionales cobrados en la venta vigente del contrato. */
    @Query("""
            select coalesce(sum(sd.quantity), 0) from SaleDetail sd
            where sd.sale.serviceContract.id = :contractId and sd.sale.status = :active
              and sd.description = :line
            """)
    long countChargedBuses(@Param("contractId") Long contractId, @Param("active") SaleStatus active,
                           @Param("line") String line);

    /** Traslados con su vehículo, para salida, llegada, cierre o cancelación. */
    @Query("""
            select t from ServiceTransport t
            join fetch t.vehicle
            join fetch t.serviceContract
            where t.id in :ids
            """)
    List<ServiceTransport> findWithVehicle(@Param("ids") Collection<Long> ids);

    /** mira si el vehiculo tiene ruta asignada, si no tiene se guarda como disponible. */
    boolean existsByVehicleIdAndStatusAndIdNotIn(Long vehicleId, TransportStatus status, Collection<Long> ids);
}
