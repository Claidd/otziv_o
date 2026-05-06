package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.Telephone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TelephoneRepository extends JpaRepository<Telephone, Long> {

    @Query("""
            SELECT t
            FROM Telephone t
            LEFT JOIN FETCH t.telephoneOperator o
            LEFT JOIN FETCH o.user u
            ORDER BY t.id
            """)
    List<Telephone> findAllWithOperator();

    @Query("""
            SELECT t
            FROM Telephone t
            LEFT JOIN FETCH t.telephoneOperator o
            LEFT JOIN FETCH o.user u
            WHERE t.id = :id
            """)
    Optional<Telephone> findByIdWithOperator(Long id);

}
