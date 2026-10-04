package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Plan funerario prepagado contratado por un cliente. Tabla: funeral_plan */
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

    @Column(name = "plan_name", length = 80)
    private String planName;

    @Column(name = "monthly_fee", precision = 10, scale = 2)
    private BigDecimal monthlyFee;

    @Column(name = "start_date")
    private LocalDate startDate;

    // ACTIVE, SUSPENDED, CANCELLED
    @Column(name = "status", length = 12)
    private String status;
}
