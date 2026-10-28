package com.example.funerariamc.persistence;

import com.example.funerariamc.model.PlanPayment;
import com.example.funerariamc.model.enums.PlanPaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**Cuotas de los planes exequiales (tabla plan_payment)*/
public interface PlanPaymentRepository extends JpaRepository<PlanPayment, Long> {

    /** Total recaudado de un mes. */
    interface MonthTotal {
        LocalDate getPeriodMonth();
        BigDecimal getTotal();
        long getPayments();
    }

    /** Recaudo por mes entre dos meses (incluidos), para la gráfica de cuotas. */
    @Query("""
            select pp.periodMonth as periodMonth, sum(pp.amount) as total, count(pp) as payments
            from PlanPayment pp
            where pp.status = :paid and pp.periodMonth between :fromMonth and :toMonth
            group by pp.periodMonth
            order by pp.periodMonth
            """)
    List<MonthTotal> totalsByMonth(@Param("paid") PlanPaymentStatus paid,
                                   @Param("fromMonth") LocalDate fromMonth,
                                   @Param("toMonth") LocalDate toMonth);

    /**Cuotas de un plan (pagadas y anuladas) con el empleado que las registró*/
    @Query("""
            select pp from PlanPayment pp
            left join fetch pp.registeredByEmployee
            where pp.funeralPlan.id = :planId
            order by pp.periodMonth desc, pp.paidAt desc
            """)
    List<PlanPayment> findHistory(@Param("planId") Long planId);

    /** Una sola cuota pagada por plan y mes (índice ux_plan_payment_plan_month_paid). */
    boolean existsByFuneralPlanIdAndPeriodMonthAndStatus(Long funeralPlanId, LocalDate periodMonth,
                                                         PlanPaymentStatus status);

    /** Una cuota con su plan y su movimiento, para anularla. */
    @Query("""
            select pp from PlanPayment pp
            join fetch pp.funeralPlan
            left join fetch pp.financialTransaction
            where pp.id = :id
            """)
    Optional<PlanPayment> findDetailById(@Param("id") Long id);
}