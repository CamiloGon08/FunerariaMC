
package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.TransactionType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents a financial transaction.
 */
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

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type")
    private TransactionType transactionType;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_sale_id", unique = true)
    private Sale relatedSale;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "transaction_date",
            insertable = false,
            updatable = false)
    private LocalDateTime transactionDate;

    @Column(name = "description")
    private String description;
}
