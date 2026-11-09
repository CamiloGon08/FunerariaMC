package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.Sale;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.SaleType;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Ventas del módulo Contratos y ventas. */
public interface SaleRepository extends JpaRepository<Sale, Long> {

    /** Fila de la tabla "Contratos registrados". */
    interface SaleRow {
        Long getSaleId();
        SaleType getSaleType();
        SaleStatus getStatus();
        LocalDateTime getSaleDate();
        BigDecimal getTotal();
        BigDecimal getPaid();
        Long getContractId();
        String getContractNumber();
        String getDeceasedName();
        String getDeceasedLastname();
        String getClientName();
        String getClientLastname();
        ClientType getClientType();
        Long getPlanId();
        String getPlanName();
    }

    /** Ventas más recientes con lo abonado.*/
    @Query("""
            select s.id as saleId, s.saleType as saleType, s.status as status, s.saleDate as saleDate,
                   s.totalAmount as total,
                   coalesce((select sum(t.amount) from FinancialTransaction t
                             where t.relatedSale = s and t.transactionType = :income and t.status = :active), 0) as paid,
                   c.id as contractId, c.contractNumber as contractNumber,
                   d.name as deceasedName, d.lastname as deceasedLastname,
                   cl.name as clientName, cl.lastname as clientLastname, cl.clientType as clientType,
                   p.id as planId, p.planName as planName
            from Sale s
            join s.client cl
            left join s.serviceContract c
            left join c.deceased d
            left join c.funeralPlan p
            order by s.id desc
            """)
    List<SaleRow> findRows(@Param("income") TransactionType income, @Param("active") TransactionStatus active,
                           Pageable pageable);

    /** Misma fila para una sola venta. */
    @Query("""
            select s.id as saleId, s.saleType as saleType, s.status as status, s.saleDate as saleDate,
                   s.totalAmount as total,
                   coalesce((select sum(t.amount) from FinancialTransaction t
                             where t.relatedSale = s and t.transactionType = :income and t.status = :active), 0) as paid,
                   c.id as contractId, c.contractNumber as contractNumber,
                   d.name as deceasedName, d.lastname as deceasedLastname,
                   cl.name as clientName, cl.lastname as clientLastname, cl.clientType as clientType,
                   p.id as planId, p.planName as planName
            from Sale s
            join s.client cl
            left join s.serviceContract c
            left join c.deceased d
            left join c.funeralPlan p
            where s.id = :saleId
            """)
    Optional<SaleRow> findRow(@Param("saleId") Long saleId, @Param("income") TransactionType income,
                              @Param("active") TransactionStatus active);

    /** Ventas de un rango de fechas (hora de la BD) con lo abonado, para el reporte de ventas de Finanzas. */
    @Query("""
            select s.id as saleId, s.saleType as saleType, s.status as status, s.saleDate as saleDate,
                   s.totalAmount as total,
                   coalesce((select sum(t.amount) from FinancialTransaction t
                             where t.relatedSale = s and t.transactionType = :income and t.status = :active), 0) as paid,
                   c.id as contractId, c.contractNumber as contractNumber,
                   d.name as deceasedName, d.lastname as deceasedLastname,
                   cl.name as clientName, cl.lastname as clientLastname, cl.clientType as clientType,
                   p.id as planId, p.planName as planName
            from Sale s
            join s.client cl
            left join s.serviceContract c
            left join c.deceased d
            left join c.funeralPlan p
            where s.saleDate >= :from and s.saleDate < :to
            order by s.saleDate, s.id
            """)
    List<SaleRow> findRowsBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                  @Param("income") TransactionType income, @Param("active") TransactionStatus active);

    /** Ventas vigentes y su total por vendedor en un rango, para la comisión del vendedor. */
    interface SellerTotal {
        Long getEmployeeId();
        long getSales();
        BigDecimal getTotal();
    }

    @Query("""
            select s.employee.id as employeeId, count(s) as sales, coalesce(sum(s.totalAmount), 0) as total
            from Sale s
            where s.status = :active and s.employee is not null
              and s.saleDate >= :from and s.saleDate < :to
            group by s.employee.id
            """)
    List<SellerTotal> sumBySeller(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                  @Param("active") SaleStatus active);

    /** Indicadores de la banda verde en una sola consulta. */
    interface SaleKpis {
        long getRecords();
        long getContracts();
        long getPrivates();
        long getProducts();
        BigDecimal getSoldThisMonth();
        BigDecimal getSoldLastMonth();
        BigDecimal getReceivable();
        long getOnCredit();
        long getVoided();
    }

    @Query(value = """
            select count(*) as records,
                   count(*) filter (where x.sale_type = 'CONTRACT') as contracts,
                   count(*) filter (where x.sale_type = 'PRIVATE') as privates,
                   count(*) filter (where x.sale_type = 'PRODUCT') as products,
                   coalesce(sum(x.total_amount) filter (where x.status = 'ACTIVE'
                            and x.sale_date >= :monthStart and x.sale_date < :nextMonth), 0) as soldThisMonth,
                   coalesce(sum(x.total_amount) filter (where x.status = 'ACTIVE'
                            and x.sale_date >= :lastMonth and x.sale_date < :monthStart), 0) as soldLastMonth,
                   coalesce(sum(x.balance) filter (where x.status = 'ACTIVE' and x.balance > 0), 0) as receivable,
                   count(*) filter (where x.status = 'ACTIVE' and x.balance > 0) as onCredit,
                   count(*) filter (where x.status = 'VOIDED') as voided
            from (select s.sale_type, s.status, s.sale_date, s.total_amount,
                         coalesce(s.total_amount, 0) - coalesce((select sum(t.amount) from financial_transaction t
                                    where t.related_sale_id = s.id and t.transaction_type = 'INCOME'
                                      and t.status = 'ACTIVE'), 0) as balance
                  from sale s) x
            """, nativeQuery = true)
    SaleKpis findKpis(@Param("lastMonth") LocalDateTime lastMonth, @Param("monthStart") LocalDateTime monthStart,
                      @Param("nextMonth") LocalDateTime nextMonth);

    /** Venta de un contrato de servicio. */
    @Query("""
            select s from Sale s
            where s.serviceContract.id = :contractId and s.status = :status
            order by s.id
            """)
    List<Sale> findByContract(@Param("contractId") Long contractId, @Param("status") SaleStatus status);

    /** Venta con su cliente, su contrato y su vendedor para el detalle. */
    @Query("""
            select s from Sale s
            join fetch s.client
            left join fetch s.serviceContract
            left join fetch s.employee
            where s.id = :id
            """)
    Optional<Sale> findDetailById(@Param("id") Long id);

    /** Clientes para el buscador de "Nuevo contrato". */
    @Query("""
            select c from Client c
            where lower(concat(c.name, ' ', coalesce(c.lastname, ''))) like :pattern
               or lower(coalesce(c.documentNumber, '')) like :pattern
            order by c.name, c.lastname, c.id
            """)
    List<Client> searchClients(@Param("pattern") String pattern, Pageable pageable);

    /** Cliente de la venta. */
    @Query("select c from Client c where c.id = :id")
    Optional<Client> findClientById(@Param("id") Long id);

    /** Difunto del contrato. */
    @Query("select d from Deceased d where d.id = :id")
    Optional<Deceased> findDeceasedById(@Param("id") Long id);
}
