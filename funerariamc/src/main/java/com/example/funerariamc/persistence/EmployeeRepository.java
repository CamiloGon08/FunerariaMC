package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Employee;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
/** Sirve para Employee y sus subtipos (Manager,Recepcionist, etc) */
public interface EmployeeRepository {
    Optional<Employee> findDocumentNumber(String documentNumber);

}
