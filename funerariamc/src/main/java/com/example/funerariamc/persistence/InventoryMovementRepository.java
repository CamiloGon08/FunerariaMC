package com.example.funerariamc.persistence;

import com.example.funerariamc.model.InventoryMovement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Entradas y salidas manuales del inventario.*/
 
public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    /** Conteo del mes para la banda verde: entradas, salidas manuales y líneas vendidas. */
    interface MonthCounts {
        long getEntries();

        long getExits();

        long getSold();
    }

    /** Las tres cifras en una sola consulta. Las fechas van en UTC, como las guarda la base. */
    @Query(value = """
            select
              (select count(*) from inventory_movement m
                 where m.movement_type = 'ENTRY' and m.movement_date >= :from and m.movement_date < :to) as entries,
              (select count(*) from inventory_movement m
                 where m.movement_type = 'EXIT' and m.movement_date >= :from and m.movement_date < :to) as exits,
              (select count(*) from sale_detail d join sale s on s.id = d.sale_id
                 where d.inventory_item_id is not null and s.status = 'ACTIVE'
                   and s.sale_date >= :from and s.sale_date < :to) as sold
            """, nativeQuery = true)
    MonthCounts countMonth(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Fila del historial manual de un artículo, con el nombre de quien lo registró. */
    interface MovementLine {
        Long getId();

        String getMovementType();

        Integer getQuantity();

        Integer getStockAfter();

        String getReason();

        LocalDateTime getMovementDate();

        String getEmployeeName();
    }

    /** Últimos movimientos manuales del artículo. */
    @Query(value = """
            select m.id as id, m.movement_type as movementType, m.quantity as quantity,
                   m.stock_after as stockAfter, m.reason as reason, m.movement_date as movementDate,
                   trim(concat(p.name, ' ', coalesce(p.lastname, ''))) as employeeName
            from inventory_movement m
            left join person p on p.id = m.employee_id
            where m.inventory_item_id = :itemId
            order by m.movement_date desc, m.id desc
            """, nativeQuery = true)
    List<MovementLine> findHistory(@Param("itemId") Long itemId, Pageable pageable);

    /** Venta del artículo hecha en Contratos y ventas. */
    interface SaleLine {
        Long getSaleId();

        Integer getQuantity();

        LocalDateTime getSaleDate();

        String getStatus();

        String getClientName();
    }

    /** Últimas ventas del artículo, la más reciente primero; las anuladas devuelven el stock. */
    @Query(value = """
            select s.id as saleId, d.quantity as quantity, s.sale_date as saleDate, s.status as status,
                   trim(concat(p.name, ' ', coalesce(p.lastname, ''))) as clientName
            from sale_detail d
            join sale s on s.id = d.sale_id
            left join person p on p.id = s.client_id
            where d.inventory_item_id = :itemId
            order by s.sale_date desc, s.id desc
            """, nativeQuery = true)
    List<SaleLine> findSales(@Param("itemId") Long itemId, Pageable pageable);
}
