package com.example.funerariamc.persistence;

import com.example.funerariamc.model.InventoryItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * Artículos del inventario: los vende Contratos y ventas y los administra
 * el módulo Inventario.
 */
public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    /** Buscador de artículos por nombre o categoría, para los diálogos. */
    @Query("""
            select i from InventoryItem i
            where lower(i.name) like :pattern or lower(coalesce(i.category, '')) like :pattern
            order by i.name, i.id
            """)
    List<InventoryItem> searchOptions(@Param("pattern") String pattern, Pageable pageable);

    /**
     * Buscador de Contratos y ventas: solo lo que el vendedor puede vender. Los insumos de
     * tanatopraxia son de uso de la funeraria y no salen en una venta.
     */
    @Query("""
            select i from InventoryItem i
            where (lower(i.name) like :pattern or lower(coalesce(i.category, '')) like :pattern)
              and coalesce(i.category, '') <> 'SUPPLY'
            order by i.name, i.id
            """)
    List<InventoryItem> searchSaleOptions(@Param("pattern") String pattern, Pageable pageable);

    /**
     * El artículo bloqueado mientras se descuenta su existencia, así dos ventas a la vez
     * no venden la misma unidad.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.id = :id")
    Optional<InventoryItem> findForUpdate(@Param("id") Long id);

    /** Todos los artículos para las tarjetas de Inventario, ordenados por nombre. */
    List<InventoryItem> findAllByOrderByNameAscIdAsc(Pageable pageable);
}
