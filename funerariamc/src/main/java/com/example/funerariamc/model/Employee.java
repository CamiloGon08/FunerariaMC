package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.model.enums.Sex;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**Guarda la información común de todos los empleados.*/
@Entity
@Table(name = "employee")
@Getter
@Setter
@NoArgsConstructor
public class Employee extends Person {

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "salary")
    private BigDecimal salary;

    // Hombre o Mujer obligatorio
    @Enumerated(EnumType.STRING)
    @Column(name = "sex", length = 6, nullable = false)
    private Sex sex;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;

    public void deactivate() {
        this.status = EmployeeStatus.INACTIVE;
    }

    public void activate() {
        this.status = EmployeeStatus.ACTIVE;
    }

    public boolean isActive() {
        return EmployeeStatus.ACTIVE.equals(this.status);
    }
}
