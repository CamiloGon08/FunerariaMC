package com.example.funerariamc.persistence;

import com.example.funerariamc.model.SaleDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Líneas de una venta: cuota base, recargos, excedente y productos. */
public interface SaleDetailRepository extends JpaRepository<SaleDetail, Long> {

    /** Líneas de una venta con su artículo de inventario. */
    @Query("""
            select d from SaleDetail d
            left join fetch d.inventoryItem
            where d.sale.id = :saleId
            order by d.id
            """)
    List<SaleDetail> findBySale(@Param("saleId") Long saleId);
}
