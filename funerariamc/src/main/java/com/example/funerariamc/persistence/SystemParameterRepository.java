package com.example.funerariamc.persistence;

import com.example.funerariamc.model.SystemParameter;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;


public interface SystemParameterRepository extends JpaRepository<SystemParameter, Long>{

    Optional<SystemParameter> findByParamName(String paramName);
    boolean existsByParamName(String paramName);

}
