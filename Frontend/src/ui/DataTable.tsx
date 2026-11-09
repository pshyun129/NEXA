import { useMemo, useState } from 'react';
import { Input, Table, Skeleton } from 'antd';
import type { TableProps } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { EmptyState } from './primitives';

/** 목록 검색창 id. '/' 단축키가 여기로 포커스를 옮긴다(설계 7.2) */
export const LIST_SEARCH_ID = 'nx-list-search';
export const focusListSearch = () => document.getElementById(LIST_SEARCH_ID)?.focus();

/** DataTable과 같은 규칙으로 검색어를 적용한다(목록 순서를 밖에서 알아야 할 때: J/K 이동, 처리 후 다음 문서) */
export function applySearch<T>(rows: T[] | undefined, q: string, searchText?: (row: T) => string): T[] {
  if (!rows) return [];
  if (!q || !searchText) return rows;
  const needle = q.toLowerCase();
  return rows.filter((r) => searchText(r).toLowerCase().includes(needle));
}

// 서버 페이지네이션이 없어 클라이언트에서 검색·정렬·20행 페이징을 한다(요구사항 1.4, 설계 7.1).
interface Props<T> extends Omit<TableProps<T>, 'dataSource' | 'pagination' | 'loading'> {
  data: T[] | undefined;
  loading?: boolean;
  /** 검색어와 비교할 텍스트를 만든다. 없으면 검색창을 숨긴다. */
  searchText?: (row: T) => string;
  searchPlaceholder?: string;
  emptyTitle?: string;
  pageSize?: number;
}

export function DataTable<T extends object>({
  data, loading, searchText, searchPlaceholder = '검색', emptyTitle = '항목이 없습니다', pageSize = 20, ...rest
}: Props<T>) {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const [page, setPage] = useState(1);

  const rows = useMemo(() => applySearch(data, q, searchText), [data, q, searchText]);

  const setQuery = (v: string) => {
    const next = new URLSearchParams(params);
    if (v) next.set('q', v); else next.delete('q');
    setParams(next, { replace: true });
    setPage(1);
  };

  if (loading && !data) {
    // 레이아웃 그대로의 정적 스켈레톤 8행(style-guide 8.10)
    return <Skeleton active={false} title={false} paragraph={{ rows: 8 }} />;
  }

  return (
    <div>
      {searchText && (
        <div style={{ marginBottom: 12, maxWidth: 320 }}>
          <Input.Search id={LIST_SEARCH_ID} allowClear defaultValue={q} placeholder={searchPlaceholder} onSearch={setQuery} aria-label={searchPlaceholder} />
        </div>
      )}
      <Table<T>
        {...rest}
        dataSource={rows}
        size="middle"
        pagination={rows.length > pageSize ? { current: page, pageSize, onChange: setPage, showSizeChanger: false, size: 'small' } : false}
        locale={{
          emptyText: q
            ? <EmptyState title="조건에 맞는 항목이 없습니다" />
            : <EmptyState title={emptyTitle} />,
        }}
      />
    </div>
  );
}
