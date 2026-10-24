package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Person;
import org.springframework.data.jpa.repository.JpaRepository;


/** Consulta la tabla person sin importar si la persona es cliente o empleado */
public interface PersonRepository extends JpaRepository<Person, Long> {

    boolean existsByDocumentNumber(String documentNumber);

    boolean existsByDocumentNumberAndIdNot(String documentNumber, Long id);

}
