
package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.SaleType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Stores the information about a sale.
 */
@Entity
@Table(name = "sale")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Sale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_type")
    private SaleType saleType = SaleType.FUNERAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_contract_id")
    private ServiceContract serviceContract;

    @Column(name = "sale_date",
            insertable = false,
            updatable = false)
    private LocalDateTime saleDate;

    @Column(name = "total_amount")
    private BigDecimal totalAmount;
}
