package com.example.funerariamc.persistence;

import com.example.funerariamc.model.AlliedPayment;
import com.example.funerariamc.model.enums.AlliedPaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Pagos a funerarias aliadas por servicios que prestaron a nuestros afiliados. */
public interface AlliedPaymentRepository extends JpaRepository<AlliedPayment, Long> {

    /** Totales de cada funeraria: saldo pendiente, pagos abiertos y servicios en un rango de fechas. */
    interface HomeTotals {
        Long getHomeId();
        BigDecimal getPending();
        long getPendingCount();
        long getServices();
        LocalDate getOldestPending();
    }

    @Query("""
            select p.alliedFuneralHome.id as homeId,
                   coalesce(sum(case when p.status = :pending then p.amount else 0 end), 0) as pending,
                   coalesce(sum(case when p.status = :pending then 1 else 0 end), 0) as pendingCount,
                   coalesce(sum(case when p.status <> :voided and p.paymentDate >= :from and p.paymentDate < :to
                                     then 1 else 0 end), 0) as services,
                   min(case when p.status = :pending then p.paymentDate end) as oldestPending
            from AlliedPayment p
            group by p.alliedFuneralHome.id
            """)
    List<HomeTotals> totalsByHome(@Param("pending") AlliedPaymentStatus pending,
                                  @Param("voided") AlliedPaymentStatus voided,
                                  @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Servicios (pagos no anulados) con fecha en un rango [from, to). */
    @Query("""
            select count(p) from AlliedPayment p
            where p.status <> :voided and p.paymentDate >= :from and p.paymentDate < :to
            """)
    long countServices(@Param("voided") AlliedPaymentStatus voided,
                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Servicios de una funeraria en un rango [from, to). */
    @Query("""
            select count(p) from AlliedPayment p
            where p.alliedFuneralHome.id = :homeId and p.status <> :voided
              and p.paymentDate >= :from and p.paymentDate < :to
            """)
    long countServicesOfHome(@Param("homeId") Long homeId, @Param("voided") AlliedPaymentStatus voided,
                             @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Pagos de una funeraria con su contrato y su egreso, del más reciente al más antiguo. */
    @Query("""
            select p from AlliedPayment p
            left join fetch p.relatedServiceContract c
            left join fetch c.deceased
            left join fetch p.financialTransaction
            where p.alliedFuneralHome.id = :homeId
            order by p.paymentDate desc, p.id desc
            """)
    List<AlliedPayment> findByHome(@Param("homeId") Long homeId);

    @Query("""
            select p from AlliedPayment p
            join fetch p.alliedFuneralHome
            left join fetch p.relatedServiceContract
            left join fetch p.financialTransaction
            where p.id = :id
            """)
    Optional<AlliedPayment> findDetailById(@Param("id") Long id);

    /** Egresos de Finanzas que son pagos a aliadas, en un rango de fechas de la BD. */
    @Query("""
            select t.id from AlliedPayment p join p.financialTransaction t
            where t.transactionDate >= :from and t.transactionDate < :to
            """)
    List<Long> findTransactionIds(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    boolean existsByFinancialTransactionId(Long transactionId);
}
