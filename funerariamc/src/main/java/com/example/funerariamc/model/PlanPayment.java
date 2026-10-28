package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.PlanPaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Entity 
@Table(name = "plan_payment")
@Getter 
@Setter 
@NoArgsConstructor 
@AllArgsConstructor 
public class PlanPayment {

    @Id 
    @GeneratedValue (strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "funeral_plan_id", nullable = false)
    private FuneralPlan funeralPlan;
    
    @Column (name = "period_month", nullable = false)
    private  LocalDate periodMonth;

    @Column (name = "amount", nullable = false, precision = 10, scale = 2)
    private  BigDecimal amount;

    @Column (name = "paid_at", nullable = false)
    private  LocalDateTime paidAt;

    @Enumerated(EnumType.STRING)
    @Column (name = "status", nullable = false, length = 7)
    private  PlanPaymentStatus status = PlanPaymentStatus.PAID;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "financial_transaction_id")
    private FinancialTransaction financialTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by_employee_id")
    private Employee registeredByEmployee;






}