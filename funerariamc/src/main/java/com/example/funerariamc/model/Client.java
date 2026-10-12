package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.ClientType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDate;

/** Client subtype of Person. Table: client. */
@Entity
@Table(name = "client")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Client extends Person {

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", length = 20)
    private ClientType clientType;

    @Column(name = "registration_date")
    private LocalDate registrationDate;
}