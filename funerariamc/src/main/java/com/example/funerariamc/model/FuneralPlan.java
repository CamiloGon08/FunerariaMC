
package com.example.funerariamc.model;

import com.example.funerariamc.utils.enums.PlanStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a prepaid or membership funeral plan.
 */
@Entity
@Table(name = "funeral_plan")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FuneralPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(name = "plan_name")
    private String planName;

    @Column(name = "monthly_fee")
    private BigDecimal monthlyFee;

    @Column(name = "start_date")
    private LocalDate startDate;

    // Si el afiliado debía más de 3 cuotas y se puso al día, la carencia vuelve a contar desde esta fecha.
    @Column(name = "waiting_restart_date")
    private LocalDate waitingRestartDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private PlanStatus status;
}
