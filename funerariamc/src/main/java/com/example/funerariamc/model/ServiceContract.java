package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Contrato de servicio funerario completo para un difunto. Tabla: service_contract */
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

    @Column(name = "contract_number", length = 20, nullable = false, unique = true)
    private String contractNumber;

    @Column(name = "contract_date")
    private LocalDate contractDate;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "status", length = 15)
    private String status;
}
