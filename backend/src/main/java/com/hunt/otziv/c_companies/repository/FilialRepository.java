package com.hunt.otziv.c_companies.repository;

import com.hunt.otziv.c_companies.model.Filial;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FilialRepository extends CrudRepository<Filial, Long> {

    Filial findByTitleAndUrl(String title, String url);

    Filial findByUrl(String url);

    java.util.Optional<Filial> findFirstByTwoGisOrganizationIdOrderByIdAsc(String identity);

    @Query(value = "SELECT filial_url FROM filial WHERE filial_id = :id", nativeQuery = true)
    @org.springframework.data.jpa.repository.QueryHints(@jakarta.persistence.QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
    String findStoredUrl(@Param("id") Long id);

    @Query("SELECT f FROM Filial f WHERE f.city.id = :cityId AND f.archived = false")
    List<Filial> findByCityId(@Param("cityId") Long cityId);

    @Query(value = """
        SELECT 
            city.city_id,
            city.city_name,
            COUNT(reviews.review_id) as unpublished_count
        FROM city
        LEFT JOIN filial ON filial.city_id = city.city_id
        LEFT JOIN reviews ON reviews.review_filial = filial.filial_id 
            AND reviews.review_publish = false
        GROUP BY city.city_id, city.city_name
        ORDER BY city.city_name
    """, nativeQuery = true)
    List<Object[]> findCitiesWithUnpublishedReviewCount();
}
