package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.model.PromoTextAssignment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromoTextAssignmentRepository extends CrudRepository<PromoTextAssignment, Long> {

    @Query("""
            SELECT assignment
            FROM PromoTextAssignment assignment
            JOIN FETCH assignment.manager manager
            LEFT JOIN FETCH manager.user user
            JOIN FETCH assignment.promoText promoText
            ORDER BY manager.id, assignment.sectionCode, assignment.buttonKey
            """)
    List<PromoTextAssignment> findAllWithDetails();

    @Query("""
            SELECT assignment
            FROM PromoTextAssignment assignment
            JOIN FETCH assignment.promoText promoText
            WHERE assignment.manager.id = :managerId
            """)
    List<PromoTextAssignment> findAllByManagerIdWithText(@Param("managerId") Long managerId);

    @Query("""
            SELECT assignment
            FROM PromoTextAssignment assignment
            JOIN FETCH assignment.manager manager
            LEFT JOIN FETCH manager.user user
            JOIN FETCH assignment.promoText promoText
            WHERE manager.id = :managerId
              AND assignment.sectionCode = :sectionCode
              AND assignment.buttonKey = :buttonKey
            """)
    Optional<PromoTextAssignment> findForSlot(
            @Param("managerId") Long managerId,
            @Param("sectionCode") String sectionCode,
            @Param("buttonKey") String buttonKey
    );

    boolean existsByPromoText_Id(Long promoTextId);
}
