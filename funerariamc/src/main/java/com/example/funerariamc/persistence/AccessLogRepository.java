package com.example.funerariamc.persistence;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.funerariamc.model.AccessLog;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    /** Últimos registros de la bitácora con el usuario y su empleado ya cargados. */
    @Query("""
            select l from AccessLog l
            join fetch l.appUser u
            join fetch u.employee
            order by l.timestamp desc, l.id desc
            """)
    List<AccessLog> findRecent(Pageable pageable);

    /**
     * Una página de la bitácora, del más reciente al más antiguo, con el total de registros.
     */
    @Query(value = """
            select l from AccessLog l
            join fetch l.appUser u
            join fetch u.employee
            order by l.timestamp desc, l.id desc
            """,
            countQuery = "select count(l) from AccessLog l")
    Page<AccessLog> findPage(Pageable pageable);

    /**
     *página filtrada por rango [from, to) y por usuario.
     */
    @Query(value = """
            select l from AccessLog l
            join fetch l.appUser u
            join fetch u.employee e
            where l.timestamp >= :from and l.timestamp < :to
              and (lower(u.username) like :userPattern
                   or lower(concat(e.name, ' ', e.lastname)) like :userPattern)
            order by l.timestamp desc, l.id desc
            """,
            countQuery = """
            select count(l) from AccessLog l
            join l.appUser u
            join u.employee e
            where l.timestamp >= :from and l.timestamp < :to
              and (lower(u.username) like :userPattern
                   or lower(concat(e.name, ' ', e.lastname)) like :userPattern)
            """)
    Page<AccessLog> findPageFiltered(@Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     @Param("userPattern") String userPattern,
                                     Pageable pageable);
}
