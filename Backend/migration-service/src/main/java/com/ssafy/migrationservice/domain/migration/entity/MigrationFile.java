package com.ssafy.migrationservice.domain.migration.entity;

import com.ssafy.migrationservice.domain.migration.entity.enums.MigrationEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 업로드 파일 메타데이터. 원본 파일은 읽은 뒤 저장하지 않고, 행 데이터만 staging_row에 남긴다. */
@Entity
@Table(name = "migration_file")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MigrationFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "migration_id", nullable = false)
    private Long migrationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity", nullable = false, length = 40)
    private MigrationEntity entity;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "headers", nullable = false, columnDefinition = "text[]")
    private String[] headers;               // 원본 헤더 (staging_row.data의 키 순서)

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "sheet_name")
    private String sheetName;               // 엑셀 시트 (CSV는 null)

    @Column(name = "header_row")
    private Integer headerRow;

    @Column(name = "total_rows", nullable = false, columnDefinition = "integer[]")
    private Integer[] totalRows;            // 합계·소계로 보고 뺀 행

    @Column(name = "rows_after_total", nullable = false, columnDefinition = "integer[]")
    private Integer[] rowsAfterTotal;       // 합계 행 아래에 있는 데이터 행

    @Column(name = "preamble", columnDefinition = "text")
    private String preamble;                // 헤더 위 제목·안내 행 (줄바꿈으로 구분). 근무 코드 범례 등

    @Builder
    private MigrationFile(Long migrationId, MigrationEntity entity, String fileName, String[] headers, int rowCount,
                          String sheetName, Integer headerRow, Integer[] totalRows, Integer[] rowsAfterTotal, String preamble) {
        this.migrationId = migrationId;
        this.entity = entity;
        this.fileName = fileName;
        this.headers = headers;
        this.rowCount = rowCount;
        this.sheetName = sheetName;
        this.headerRow = headerRow;
        this.totalRows = totalRows == null ? new Integer[0] : totalRows;
        this.rowsAfterTotal = rowsAfterTotal == null ? new Integer[0] : rowsAfterTotal;
        this.preamble = preamble;
        this.uploadedAt = LocalDateTime.now();
    }
}
