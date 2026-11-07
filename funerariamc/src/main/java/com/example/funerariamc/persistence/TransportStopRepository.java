package com.example.funerariamc.persistence;

import com.example.funerariamc.model.TransportStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Paradas de cada traslado (salida, recogida, ceremonia y destino). */
public interface TransportStopRepository extends JpaRepository<TransportStop, Long> {

    /** Paradas de varios traslados en una sola consulta. */
    @Query("""
            select s from TransportStop s
            where s.serviceTransport.id in :transportIds
            order by s.serviceTransport.id, s.stopOrder
            """)
    List<TransportStop> findByTransportIds(@Param("transportIds") Collection<Long> transportIds);
}
