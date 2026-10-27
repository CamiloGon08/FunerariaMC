package com.example.funerariamc.persistence;

import com.example.funerariamc.model.FinancialTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialTransactionRepository extends JpaRepository<FinancialTransction, Long>{

}
