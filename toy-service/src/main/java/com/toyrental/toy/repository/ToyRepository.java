package com.toyrental.toy.repository;

import com.toyrental.toy.entity.Toy;
import com.toyrental.toy.entity.ToyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ToyRepository extends JpaRepository<Toy, String> {

    Optional<Toy> findByIdAndActiveTrue(String id);

    @Query("SELECT t FROM Toy t WHERE t.active = true "
            + "AND (:category IS NULL OR t.category = :category) "
            + "AND (:ageGroup IS NULL OR t.ageGroup = :ageGroup)")
    Page<Toy> browse(@Param("category") String category, @Param("ageGroup") String ageGroup, Pageable pageable);

    @Query("SELECT t FROM Toy t WHERE t.active = true "
            + "AND (LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(t.brand) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + "AND (:category IS NULL OR t.category = :category) "
            + "AND (:ageGroup IS NULL OR t.ageGroup = :ageGroup)")
    Page<Toy> search(@Param("q") String query, @Param("category") String category,
                      @Param("ageGroup") String ageGroup, Pageable pageable);

    @Query("SELECT DISTINCT t.category FROM Toy t WHERE t.active = true ORDER BY t.category")
    List<String> findDistinctCategories();

    @Query("SELECT DISTINCT t.ageGroup FROM Toy t WHERE t.active = true ORDER BY t.ageGroup")
    List<String> findDistinctAgeGroups();

    Page<Toy> findByActiveTrueAndStatusNot(ToyStatus status, Pageable pageable);

    Page<Toy> findByActiveTrueAndStatus(ToyStatus status, Pageable pageable);

    Page<Toy> findByActiveTrue(Pageable pageable);

}
