package com.example.funerariamc.persistence;

import com.example.funerariamc.model.ServiceTransport;
import com.example.funerariamc.model.enums.StopType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
