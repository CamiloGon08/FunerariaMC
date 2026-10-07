package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Subtipo de Person: quien trabaja para la funeraria. Tabla: employee */
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

    // ACTIVE o INACTIVE
    @Column(name = "status", length = 10)
    private String status = "ACTIVE";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;
    
    public void deactivate(){
        this.status = "INACTIVE";
    }
    
    public void activate(){
        this.status = "ACTIVE";
    }
    
    public boolean isActive(){
        return "ACTIVE".equals(this.status);
    }
    
    public void setSalary(BigDecimal salary){
        this.salary = salary;
    }
}

