package com.example.funerariamc.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDate;

/** Subtipo de Person: quien contrata servicios funerarios. Tabla: client */
@Entity
@Table(name = "client")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Client extends Person {

    // NATURAL o CORPORATE
    @Column(name = "client_type", length = 15)
    private String clientType;

    @Column(name = "registration_date")
    private LocalDate registrationDate;
}
