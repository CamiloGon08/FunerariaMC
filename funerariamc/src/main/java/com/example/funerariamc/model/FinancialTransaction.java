package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Movimiento financiero (ingreso o egreso). Tabla: financial_transaction */
@Entity
@Table(name = "financial_transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FinancialTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // INCOME o EXPENSE
    @Column(name = "transaction_type", length = 10)
    private String transactionType;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_sale_id", unique = true)
    private Sale relatedSale;

    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "transaction_date")
    private LocalDateTime transactionDate;

    @Column(name = "description", length = 150)
    private String description;
}
