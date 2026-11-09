import { PageHeader, Card, EmptyState } from '@/ui/primitives';

/** 아직 구현하지 않은 화면. 몇 주차에 만드는지 적는다(설계 9장). */
export function Placeholder({ title, week }: { title: string; week: string }) {
  return (
    <>
      <PageHeader title={title} />
      <Card>
        <EmptyState title="화면 준비 중" description={`${week}에 구현할 화면입니다`} />
      </Card>
    </>
  );
}
