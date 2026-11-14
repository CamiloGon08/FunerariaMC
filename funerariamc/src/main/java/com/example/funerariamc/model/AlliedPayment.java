package com.example.funerariamc.model;

import com.example.funerariamc.utils.enums.AlliedPaymentStatus;
import com.example.funerariamc.utils.enums.PaymentMethod;
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

    // Transferencia o consignación; null en pagos registrados antes de pedir el medio.
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 10)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AlliedPaymentStatus status = AlliedPaymentStatus.PENDING;

    // Egreso que se registra en Finanzas cuando el pago se marca como pagado
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "financial_transaction_id")
    private FinancialTransaction financialTransaction;
}