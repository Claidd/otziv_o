package com.hunt.otziv.l_lead.repository;

import com.hunt.otziv.l_lead.dto.api.AdminPhoneListRow;
import com.hunt.otziv.l_lead.model.Telephone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    @Query("""
            SELECT new com.hunt.otziv.l_lead.dto.api.AdminPhoneListRow(
                t.id,
                t.number,
                t.fio,
                t.birthday,
                t.amountAllowed,
                t.amountSent,
                t.blockTime,
                t.timer,
                CASE WHEN t.googleLogin IS NULL OR TRIM(t.googleLogin) = '' THEN false ELSE true END,
                CASE WHEN t.googlePassword IS NULL OR TRIM(t.googlePassword) = '' THEN false ELSE true END,
                CASE WHEN t.avitoPassword IS NULL OR TRIM(t.avitoPassword) = '' THEN false ELSE true END,
                CASE WHEN t.mailLogin IS NULL OR TRIM(t.mailLogin) = '' THEN false ELSE true END,
                CASE WHEN t.mailPassword IS NULL OR TRIM(t.mailPassword) = '' THEN false ELSE true END,
                t.foto_instagram,
                t.active,
                t.createDate,
                t.updateStatus,
                o.id,
                u.fio
            )
            FROM Telephone t
            LEFT JOIN t.telephoneOperator o
            LEFT JOIN o.user u
            WHERE (:unrestricted = true OR o.id IN :allowedOperatorIds)
              AND (
                    :keyword = ''
                    OR LOWER(COALESCE(t.number, '')) LIKE CONCAT('%', :keyword, '%')
                    OR LOWER(COALESCE(t.fio, '')) LIKE CONCAT('%', :keyword, '%')
                    OR LOWER(COALESCE(t.foto_instagram, '')) LIKE CONCAT('%', :keyword, '%')
                    OR LOWER(COALESCE(u.fio, '')) LIKE CONCAT('%', :keyword, '%')
              )
            ORDER BY t.id
            """)
    List<AdminPhoneListRow> findAdminPhoneRows(
            @Param("unrestricted") boolean unrestricted,
            @Param("allowedOperatorIds") Collection<Long> allowedOperatorIds,
            @Param("keyword") String keyword
    );

}
