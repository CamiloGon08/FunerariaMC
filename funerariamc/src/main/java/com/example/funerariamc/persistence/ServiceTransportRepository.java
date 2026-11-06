package com.example.funerariamc.persistence;

import com.example.funerariamc.model.ServiceTransport;
import com.example.funerariamc.model.enums.StopType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

// Traslados de un contrato de servicio. Las pantallas de Transporte agregan sus consultas aquí.
public interface ServiceTransportRepository extends JpaRepository<ServiceTransport, Long>{
    /** Traslado con su vehículo, conductor y el lugar de destino, si lo tiene*/
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

    /** Destino del traslado de salida de cada contrato.*/
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
}
