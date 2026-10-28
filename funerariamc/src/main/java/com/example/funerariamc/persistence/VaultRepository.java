package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Vault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VaultRepository extends JpaRepository<Vault, Long> {

    /** Todas las bóvedas de un cementerio con su titular y su difunto, para dibujar el muro. */
    @Query("""
            select v from Vault v
            left join fetch v.client
            left join fetch v.deceased
            where v.cemetery.id = :cemeteryId
            order by v.code
            """)
    List<Vault> findWallByCemetery(@Param("cemeteryId") Long cemeteryId);

    /** Una bóveda con su cementerio, titular y difunto, para el panel de detalle. */
    @Query("""
            select v from Vault v
            join fetch v.cemetery
            left join fetch v.client
            left join fetch v.deceased
            where v.id = :id
            """)
    Optional<Vault> findDetailById(@Param("id") Long id);

    /** Bóveda donde reposa un difunto, con su cementerio (destino en el expediente de Difuntos). */
    @Query("""
            select v from Vault v
            join fetch v.cemetery
            where v.deceased.id = :deceasedId
            """)
    Optional<Vault> findByDeceasedWithCemetery(@Param("deceasedId") Long deceasedId);

    /** Un difunto reposa en una sola bóveda a la vez. */
    boolean existsByDeceasedId(Long deceasedId);
}

