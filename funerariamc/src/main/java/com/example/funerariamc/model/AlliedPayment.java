package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Pago hacia/desde una funeraria aliada por un servicio compartido. Tabla: allied_payment */
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

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "status", length = 15)
    private String status;
}
