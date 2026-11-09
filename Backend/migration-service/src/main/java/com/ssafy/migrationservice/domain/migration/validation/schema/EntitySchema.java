package com.ssafy.migrationservice.domain.migration.validation.schema;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;

import java.util.List;
import java.util.Optional;

public record EntitySchema(MigrationEntity entity, List<FieldSpec> fields) {

    public Optional<FieldSpec> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    public List<FieldSpec> keyFields() {
        return fields.stream().filter(FieldSpec::key).toList();
    }
}
