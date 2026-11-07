package com.example.funerariamc.persistence;

import com.example.funerariamc.model.CremationProvider;
import com.example.funerariamc.model.enums.CremationPurchaseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Proveedores de cremación y el saldo de cupos prepagados de cada uno. */
public interface CremationProviderRepository extends JpaRepository<CremationProvider, Long> {

    /**
     * Saldo de un proveedor. Disponibles = recibidos - separados - cremados. En camino son los
     * cupos de compras confirmadas que aún no llegan; solicitudes son las compras por confirmar.
     */
    interface StockRow {
        Long getId();
        String getCode();
        String getName();
        Integer getMinimumAvailable();
        Boolean getActive();
        Long getReceived();
        Long getReserved();
        Long getUsed();
        Long getIncoming();
        Long getRequests();
    }

    /** Todo el saldo de los proveedores en una sola consulta, ordenado por código. */
    @Query("""
            select p.id as id, p.code as code, p.name as name, p.minimumAvailable as minimumAvailable,
                   p.active as active,
                   (select coalesce(sum(r.quantity), 0) from CremationPurchase r
                     where r.provider = p and r.status = :received) as received,
                   (select count(c.id) from Cremation c
                     where c.cremationPurchase.provider = p and c.completedAt is null) as reserved,
                   (select count(c.id) from Cremation c
                     where c.cremationPurchase.provider = p and c.completedAt is not null) as used,
                   (select coalesce(sum(o.quantity), 0) from CremationPurchase o
                     where o.provider = p and o.status = :ordered) as incoming,
                   (select count(q.id) from CremationPurchase q
                     where q.provider = p and q.status = :requested) as requests
            from CremationProvider p
            order by p.code
            """)
    List<StockRow> findStock(@Param("received") CremationPurchaseStatus received,
                             @Param("ordered") CremationPurchaseStatus ordered,
                             @Param("requested") CremationPurchaseStatus requested);

    Optional<CremationProvider> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    /** Bloquea al proveedor mientras se separa un cupo, para que dos usuarios no tomen el mismo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CremationProvider p where p.code = :code")
    Optional<CremationProvider> lockByCode(@Param("code") String code);
}
