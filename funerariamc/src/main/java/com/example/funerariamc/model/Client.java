
package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.ClientType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;

/** Representa a un cliente que contrata servicios o planes funerarios*/
@Entity
@Table(name = "client")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Client extends Person {

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", length = 20)
    private ClientType clientType;

    /**Muestra la hora en hora de bogota */
    @Column(name = "registration_date", updatable = false)
    private LocalDate registrationDate;
}
