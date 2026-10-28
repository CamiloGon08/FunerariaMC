package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Beneficiary;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.enums.PlanStatus;
import com.example.funerariamc.model.enums.SaleType;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ClientOverviewRepository extends Repository<Client, Long> {

    /**plan exequial con su numero de beeficiario */
    interface PlanRow{
        Long getClientId();
        Long getPlanId();
        String getPlanName();
        LocalDate getStartDate();
        PlanStatus getStatus();
        long getBeneficiaries();
    }

    /**venta del cliente con la que ya abono */
    interface SaleRow{
        Long getSaleId();
        Long getClientId();
        SaleType getSaleType();
        LocalDateTime getSaleDate();
        BigDecimal getTotal();
        BigDecimal getPaid();
        String getContractNumber();
    }

    /**Numero de contratos de servicio por cliente */
    interface ContractCount{
        Long getClientId();
        long getContracts();
    }

    /** difunto atendido por un contrato del cliente, con sola e inicio de velacion */
    interface DeceasedRow{
        String getName();
        String getLastname();
        LocalDate getDeathDate();
        String getContractNumber();
        LocalDate getContractDate();
        String getWakeRoom();
        LocalDateTime getWakeStartAt();
    }

    interface ReceivableSummary{
        BigDecimal getReceivable();
        long getSalesWithBalance();
    }

     @Query("""
            select p.client.id as clientId, p.id as planId, p.planName as planName,
                   p.startDate as startDate, p.status as status,
                   (select count(b) from Beneficiary b where b.funeralPlan = p) as beneficiaries
            from FuneralPlan p
            where p.client.id in :clientIds
            order by p.startDate desc, p.id desc
            """)
    List<PlanRow> findPlans(@Param("clientIds") Collection<Long> clientIds);

    @Query("""
            select s.id as saleId, s.client.id as clientId, s.saleType as saleType,
                   s.saleDate as saleDate, s.totalAmount as total,
                   (select coalesce(sum(t.amount), 0) from FinancialTransaction t
                    where t.relatedSale = s and t.transactionType = :income and t.status = :active) as paid,
                   sc.contractNumber as contractNumber
            from Sale s left join s.serviceContract sc
            where s.client.id in :clientIds
            order by s.saleDate desc, s.id desc
            """)
    List<SaleRow> findSales(@Param("clientIds") Collection<Long> clientIds,
                            @Param("income") TransactionType income,
                            @Param("active") TransactionStatus active);

    @Query("""
            select c.client.id as clientId, count(c) as contracts
            from ServiceContract c
            where c.client.id in :clientIds
            group by c.client.id
            """)
    List<ContractCount> countContracts(@Param("clientIds") Collection<Long> clientIds);

    @Query("""
            select b from Beneficiary b
            where b.funeralPlan.id = :planId
            order by b.id
            """)
    List<Beneficiary> findBeneficiaries(@Param("planId") Long planId);

    @Query("""
            select d.name as name, d.lastname as lastname, d.deathDate as deathDate,
                   c.contractNumber as contractNumber, c.contractDate as contractDate,
                   w.name as wakeRoom, c.wakeStartAt as wakeStartAt
            from ServiceContract c join c.deceased d left join c.wakeRoom w
            where c.client.id = :clientId
            order by c.contractDate desc, c.id desc
            """)
    List<DeceasedRow> findServedDeceased(@Param("clientId") Long clientId);

    @Query("""
            select count(distinct p.client.id) from FuneralPlan p
            where p.status = :status
            """)
    long countClientsWithPlan(@Param("status") PlanStatus status);

    /** Suma en PostgreSQL el saldo de cada venta (total menos abonos activos) y se queda con los positivos. */
    @Query(value = """
            select coalesce(sum(x.balance), 0) as receivable, count(*) as salesWithBalance
            from (select coalesce(s.total_amount, 0)
                         - coalesce((select sum(t.amount) from financial_transaction t
                                     where t.related_sale_id = s.id
                                       and t.transaction_type = 'INCOME'
                                       and t.status = 'ACTIVE'), 0) as balance
                  from sale s) x
            where x.balance > 0
            """, nativeQuery = true)
    ReceivableSummary receivableSummary();

    /** Los indicadores de Clientes en una sola consulta (antes eran 5 viajes a Supabase). */
    interface ClientCounts {
        long getTotal();
        long getNaturalClients();
        long getWithPlan();
        BigDecimal getReceivable();
        long getSalesWithBalance();
        long getNewThisMonth();
    }

    @Query(value = """
            select (select count(*) from client) as total,
                   (select count(*) from client where client_type = 'NATURAL') as naturalClients,
                   (select count(distinct p.client_id) from funeral_plan p where p.status = 'ACTIVE') as withPlan,
                   coalesce(r.receivable, 0) as receivable,
                   r.sales_with_balance as salesWithBalance,
                   (select count(*) from client where registration_date >= :firstOfMonth) as newThisMonth
            from (select sum(x.balance) as receivable, count(*) as sales_with_balance
                  from (select coalesce(s.total_amount, 0)
                               - coalesce((select sum(t.amount) from financial_transaction t
                                           where t.related_sale_id = s.id
                                             and t.transaction_type = 'INCOME'
                                             and t.status = 'ACTIVE'), 0) as balance
                        from sale s) x
                  where x.balance > 0) r
            """, nativeQuery = true)
    ClientCounts countClients(@Param("firstOfMonth") LocalDate firstOfMonth);

}
