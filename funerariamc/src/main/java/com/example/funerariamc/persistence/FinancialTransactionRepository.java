package com.example.funerariamc.persistence;

import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, Long>{

    /** Abonos de una venta, vigentes y anulados, del más antiguo al más reciente. */
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
}
