package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** Sala de velación disponible en la funeraria. Tabla: wake_room */
@Entity
@Table(name = "wake_room")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WakeRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 15, nullable = false)
    private String name;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "base_price", precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "surcharge_applies")
    private Boolean surchargeApplies = false;
}
