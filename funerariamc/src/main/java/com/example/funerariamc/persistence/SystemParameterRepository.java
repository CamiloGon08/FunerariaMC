package com.example.funerariamc.persistence;

import com.example.funerariamc.model.SystemParameter;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;


public interface SystemParameterRepository extends JpaRepository<SystemParameter, Long>{

    Optional<SystemParameter> findByParamName(String paramName);
    boolean existsByParamName(String paramName);

    /** muestra todos los parámetros con quién los cambió por última vez. */
    @Query("""
            select p from SystemParameter p
            left join fetch p.updatedBy u
            left join fetch u.employee
            order by p.category, p.paramName
            """)
    List<SystemParameter> findAllWithEditor();

}
