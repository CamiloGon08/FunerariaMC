package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.CremationPurchaseStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Compra en lote de cremaciones prepagadas a un proveedor. Cada cremación que la usa queda ligada
 * a ella (cremation.cremation_purchase_id): separada mientras no tiene certificado y restada
 * cuando ya se cremó. Al confirmarla se registra el gasto en Finanzas.
 */
@Entity
@Table(name = "cremation_purchase")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CremationPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", nullable = false)
    private CremationProvider provider;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 15, nullable = false)
    private CremationPurchaseStatus status = CremationPurchaseStatus.REQUESTED;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    // Quién registró la compra (null si la creó n8n).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    // Gasto registrado en Finanzas al confirmar la compra.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "financial_transaction_id")
    private FinancialTransaction financialTransaction;
}
