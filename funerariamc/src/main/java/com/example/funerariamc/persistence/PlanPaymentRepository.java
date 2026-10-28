package com.example.funerariamc.persistence;

import com.example.funerariamc.model.PlanPayment;
import com.example.funerariamc.model.enums.PlanPaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Cuotas de los planes exequiales (tabla plan_payment). Solo cuentan las cuotas PAID;
 * una cuota anulada (VOIDED) se conserva pero no suma.
 */
public interface PlanPaymentRepository extends JpaRepository<PlanPayment, Long> {

    /** Cuotas pagadas por plan hasta un mes, para saber cuántas debe (mora). */
    interface PaidSummary {
        Long getPlanId();
        long getPaidMonths();
        LocalDateTime getLastPaidAt();
    }

    /** Total recaudado de un mes. */
    interface MonthTotal {
        LocalDate getPeriodMonth();
        BigDecimal getTotal();
        long getPayments();
    }

    @Query("""
            select pp.funeralPlan.id as planId, count(pp) as paidMonths, max(pp.paidAt) as lastPaidAt
            from PlanPayment pp
            where pp.status = :paid and pp.periodMonth <= :untilMonth
            group by pp.funeralPlan.id
            """)
    List<PaidSummary> summarizePaid(@Param("paid") PlanPaymentStatus paid,
                                    @Param("untilMonth") LocalDate untilMonth);

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

    /** Cuotas de un plan (pagadas y anuladas), de la más reciente a la más antigua. */
    List<PlanPayment> findByFuneralPlanIdOrderByPeriodMonthDescPaidAtDesc(Long funeralPlanId);

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
