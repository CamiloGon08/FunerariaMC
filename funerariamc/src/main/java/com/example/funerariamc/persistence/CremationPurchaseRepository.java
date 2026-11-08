package com.example.funerariamc.persistence;

import com.example.funerariamc.model.CremationPurchase;
import com.example.funerariamc.model.enums.CremationPurchaseStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Compras de cremaciones prepagadas. */
public interface CremationPurchaseRepository extends JpaRepository<CremationPurchase, Long> {

    /** Compras con su proveedor y quién las registró, de la más reciente a la más antigua. */
    @Query("""
            select cp from CremationPurchase cp
            join fetch cp.provider
            left join fetch cp.employee
            order by cp.requestedAt desc, cp.id desc
            """)
    List<CremationPurchase> findAllWithProvider();

    /** Compra con su proveedor y su gasto, para confirmarla, recibirla o anularla. */
    @Query("""
            select cp from CremationPurchase cp
            join fetch cp.provider
            left join fetch cp.financialTransaction
            where cp.id = :id
            """)
    Optional<CremationPurchase> findDetailById(@Param("id") Long id);

    /**
     * Compras recibidas del proveedor que todavía tienen cupos libres, de la más antigua a la más
     * reciente: el cupo se toma de la primera.
     */
    @Query("""
            select cp from CremationPurchase cp
            where cp.provider.id = :providerId and cp.status = :received
              and cp.quantity > (select count(c.id) from Cremation c where c.cremationPurchase = cp)
            order by cp.receivedAt, cp.id
            """)
    List<CremationPurchase> findWithFreeSlots(@Param("providerId") Long providerId,
                                              @Param("received") CremationPurchaseStatus received,
                                              Pageable page);
}
