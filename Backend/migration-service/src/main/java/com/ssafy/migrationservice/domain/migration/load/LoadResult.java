package com.ssafy.migrationservice.domain.migration.load;

public record LoadResult(int created, int updated) {

    LoadResult plus(LoadResult other) {
        return new LoadResult(created + other.created, updated + other.updated);
    }
}
