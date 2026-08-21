package com.toyrental.toy.repository;

import com.toyrental.toy.entity.ToyImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToyImageRepository extends JpaRepository<ToyImage, String> {

    List<ToyImage> findByToyIdOrderBySortOrderAsc(String toyId);

    List<ToyImage> findByToyIdInOrderBySortOrderAsc(List<String> toyIds);

}
