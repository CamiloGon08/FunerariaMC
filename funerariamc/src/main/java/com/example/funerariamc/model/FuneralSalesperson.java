package com.example.funerariamc.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** Subtipo de Employee: vendedor de servicios funerarios. Tabla: funeral_salesperson */
@Entity
@Table(name = "funeral_salesperson")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FuneralSalesperson extends Employee {

    @Column(name = "commission_rate", precision = 5, scale = 2)
    private BigDecimal commissionRate;
}
