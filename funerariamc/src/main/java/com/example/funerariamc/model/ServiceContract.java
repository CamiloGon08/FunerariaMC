package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.ContractStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Stores the details of a funeral service contract.
 */
@Entity
@Table(name = "service_contract")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ServiceContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deceased_id", nullable = false, unique = true)
    private Deceased deceased;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wake_room_id")
    private WakeRoom wakeRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "funeral_salesperson_id")
    private FuneralSalesperson funeralSalesperson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "funeral_plan_id")
    private FuneralPlan funeralPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thanatopractor_id")
    private Thanatopractor thanatopractor;

    @Column(name = "contract_number", nullable = false, unique = true)
    private String contractNumber;

    @Column(name = "contract_date", insertable = false, updatable = false)
    private LocalDate contractDate;

    @Column(name = "preparation_completed_at")
    private LocalDateTime preparationCompletedAt;

    @Column(name = "wake_start_at")
    private LocalDateTime wakeStartAt;

    @Column(name = "wake_end_at")
    private LocalDateTime wakeEndAt;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ContractStatus status = ContractStatus.ACTIVE;
}
