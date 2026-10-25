package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.AlliedPaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Represents a payment made to an allied funeral home.
 */
@Entity
@Table(name = "allied_payment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AlliedPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allied_funeral_home_id", nullable = false)
    private AlliedFuneralHome alliedFuneralHome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_service_contract_id")
    private ServiceContract relatedServiceContract;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AlliedPaymentStatus status = AlliedPaymentStatus.PENDING;
}