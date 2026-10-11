package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.EmployeeStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Employee subtype of Person. Table: employee. */
@Entity
@Table(name = "employee")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Employee extends Person {

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "salary", precision = 10, scale = 2)
    private BigDecimal salary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10)
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

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }
}