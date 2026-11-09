package com.ssafy.migrationservice.domain.migration.mapping;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ValueSuggestionRepository extends JpaRepository<ValueSuggestion, Long> {

    List<ValueSuggestion> findByFileIdAndField(Long fileId, String field);
}
