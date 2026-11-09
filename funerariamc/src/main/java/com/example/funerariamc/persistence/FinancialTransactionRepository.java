package com.example.funerariamc.persistence;

import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, Long>{

    /** Abonos de una venta, vigentes y anulados. */
    @Query("""
            select t from FinancialTransaction t
            left join fetch t.employee
            where t.relatedSale.id = :saleId and t.transactionType = :income
            order by t.id
            """)
    List<FinancialTransaction> findIncomeBySale(@Param("saleId") Long saleId,
                                                @Param("income") TransactionType income);

    /** Suma de los abonos vigentes de una venta; el saldo es el total menos esta suma. */
    @Query("""
            select coalesce(sum(t.amount), 0) from FinancialTransaction t
            where t.relatedSale.id = :saleId and t.transactionType = :income and t.status = :active
            """)
    BigDecimal sumIncomeBySale(@Param("saleId") Long saleId, @Param("income") TransactionType income,
                               @Param("active") TransactionStatus active);

    /** Movimientos de un rango, con quién lo registró y la venta. */
    @Query("""
            select t from FinancialTransaction t
            left join fetch t.employee
            left join fetch t.relatedSale s
            left join fetch s.serviceContract
            left join fetch t.financeAccount
            where t.transactionDate >= :from and t.transactionDate < :to
            order by t.transactionDate desc, t.id desc
            """)
    List<FinancialTransaction> findInRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Movimientos del rango que son cuotas de planes exequiales. */
    @Query("""
            select p.financialTransaction.id from PlanPayment p
            where p.financialTransaction.transactionDate >= :from and p.financialTransaction.transactionDate < :to
            """)
    List<Long> findPlanPaymentIds(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Movimientos del rango que son compras de cremaciones prepagadas. */
    @Query("""
            select p.financialTransaction.id from CremationPurchase p
            where p.financialTransaction.transactionDate >= :from and p.financialTransaction.transactionDate < :to
            """)
    List<Long> findCremationPurchaseIds(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Total vigente de ingresos y de egresos de un rango. */
    interface TypeTotal {
        TransactionType getType();
        BigDecimal getTotal();
    }

    @Query("""
            select t.transactionType as type, coalesce(sum(t.amount), 0) as total
            from FinancialTransaction t
            where t.status = :active and t.transactionDate >= :from and t.transactionDate < :to
            group by t.transactionType
            """)
    List<TypeTotal> sumByType(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                              @Param("active") TransactionStatus active);

    /** Totales vigentes por mes y tipo, para la gráfica del balance del año. */
    interface MonthTotal {
        int getMonth();
        String getType();
        BigDecimal getTotal();
    }

    @Query(value = """
            select cast(extract(month from (t.transaction_date at time zone 'UTC' at time zone 'America/Bogota')) as int) as month,
                   t.transaction_type as type, coalesce(sum(t.amount), 0) as total
            from financial_transaction t
            where t.status = 'ACTIVE' and t.transaction_date >= :from and t.transaction_date < :to
            group by 1, 2
            """, nativeQuery = true)
    List<MonthTotal> sumByMonth(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Comisiones vigentes de un período. */
    @Query("""
            select t from FinancialTransaction t
            join fetch t.employee
            where t.transactionType = :expense and t.status = :active and t.description like :prefix
            """)
    List<FinancialTransaction> findCommissions(@Param("prefix") String prefix,
                                               @Param("expense") TransactionType expense,
                                               @Param("active") TransactionStatus active);

    boolean existsByEmployeeIdAndTransactionTypeAndStatusAndDescriptionStartingWith(
            Long employeeId, TransactionType type, TransactionStatus status, String prefix);

    /** Movimiento con su venta, para anularlo. */
    @Query("""
            select t from FinancialTransaction t
            left join fetch t.relatedSale
            where t.id = :id
            """)
    Optional<FinancialTransaction> findDetailById(@Param("id") Long id);

    @Query("select count(p) > 0 from PlanPayment p where p.financialTransaction.id = :id")
    boolean isPlanPayment(@Param("id") Long id);

    @Query("select count(p) > 0 from CremationPurchase p where p.financialTransaction.id = :id")
    boolean isCremationPurchase(@Param("id") Long id);
}
