package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.model.enums.Sex;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Datos del formulario de Personal. Los campos de un cargo que no aplica
 * llegan en null (por ejemplo, licencia para un tanatólogo).
 */
public record EmployeeData(
        // person
        String name,
        String lastname,
        String documentType,
        String documentNumber,
        String phone,
        String email,
        String address,
        LocalDate birthDate,
        // employee
        Sex sex,
        EmployeePosition position,
        LocalDate hireDate,
        BigDecimal salary,
        // funeral_salesperson
        BigDecimal commissionRate,
        // thanatopractor (RN-18: sin número de licencia)
        String trainingInstitution,
        LocalDate certificationDate,
        String certificateFileUrl,
        BigDecimal commissionPerCase,
        // driver
        String licenseCategory,
        String licenseNumber,
        LocalDate licenseExpiryDate,
        String licenseFileUrl) {
}
