package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.enums.EmployeeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
/** Sirve para Employee y sus subtipos (Manager,Recepcionist, etc) */
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByDocumentNumber(String documentNumber);

    /**Pagina de la lista de personal */
    @EntityGraph(attributePaths = "role")
    @Query(value = """
            select e from Employee e
            where e.status in :statuses
              and type(e) in :types
              and (lower(concat(e.name, ' ', e.lastname)) like :pattern
                   or e.documentNumber like :pattern)
            order by e.lastname, e.name, e.id
            """,
            countQuery = """
            select count(e) from Employee e
            where e.status in :statuses
              and type(e) in :types
              and (lower(concat(e.name, ' ', e.lastname)) like :pattern
                   or e.documentNumber like :pattern)
            """)
    Page<Employee> search(@Param("pattern") String patetrn,
                        @Param("statuses") Collection<EmployeeStatus> statuses,
                        @Param("types") Collection<Class<? extends Employee>> types,
                        Pageable pageable);

    /**resume numero de empleados, activos e inactivos */
    long countByStatus(EmployeeStatus status);

}
