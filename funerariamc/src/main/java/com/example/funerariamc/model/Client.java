
package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.ClientType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;

/**
 * Represents a client who contracts funeral services or plans.
 */
@Entity
@Table(name = "client")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Client extends Person {

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", length = 20)
    private ClientType clientType;

    @Column(name = "registration_date",
            insertable = false,
            updatable = false)
    private LocalDate registrationDate;
}
