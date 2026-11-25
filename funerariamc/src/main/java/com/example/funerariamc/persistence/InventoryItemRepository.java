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
            where cast(function('translate', lower(concat(' ', i.name, ' ', coalesce(i.category, ''))), 'áéíóúüñ', 'aeiouun') as String) like :pattern
            order by case when cast(function('translate', lower(concat(' ', i.name)), 'áéíóúüñ', 'aeiouun') as String) like substring(:pattern, 2) then 0 else 1 end, i.name, i.id
            """)
    List<InventoryItem> searchOptions(@Param("pattern") String pattern, Pageable pageable);

    /**
     * Buscador de Contratos y ventas: solo lo que el vendedor puede vender. Los insumos de
     * tanatopraxia son de uso de la funeraria y no salen en una venta.
     */
    @Query("""
            select i from InventoryItem i
            where (cast(function('translate', lower(concat(' ', i.name, ' ', coalesce(i.category, ''))), 'áéíóúüñ', 'aeiouun') as String) like :pattern)
              and coalesce(i.category, '') <> 'SUPPLY'
            order by case when cast(function('translate', lower(concat(' ', i.name)), 'áéíóúüñ', 'aeiouun') as String) like substring(:pattern, 2) then 0 else 1 end, i.name, i.id
            """)
    List<InventoryItem> searchSaleOptions(@Param("pattern") String pattern, Pageable pageable);

    /**
     * El artículo bloqueado mientras se descuenta su existencia, así dos ventas a la vez
     * no venden la misma unidad.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.id = :id")
    Optional<InventoryItem> findForUpdate(@Param("id") Long id);

    /** Todos los artículos para las tarjetas de Inventario.*/
    List<InventoryItem> findAllByOrderByNameAscIdAsc(Pageable pageable);

    /** Artículos en el nivel mínimo o por debajo. */
    @Query("""
            select i from InventoryItem i
            where coalesce(i.stockQuantity, 0) <= coalesce(i.minimumStock, 0)
            order by coalesce(i.stockQuantity, 0) - coalesce(i.minimumStock, 0), i.name
            """)
    List<InventoryItem> findAtOrBelowMinimum(Pageable pageable);
}
