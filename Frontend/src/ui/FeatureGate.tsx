import type { ReactNode } from 'react';
import { features, featureNotice, type FeatureFlag } from '@/config/features';
import { Icon } from './Icon';
import { CircleHelp } from './icons';
import s from './ui.module.css';

/** 백엔드 갭이 있는 영역을 "연동 대기" 안내로 감싼다(설계 7.1). */
export function FeatureGate({ flag, children }: { flag: FeatureFlag; children: ReactNode }) {
  if (features[flag]) return <>{children}</>;
  const notice = featureNotice[flag];
  return (
    <div className={s.panel} role="note">
      <Icon as={CircleHelp} size={20} className={s.panelInfo} />
      <div className={s.panelBody}>
        <div className={s.panelTitle}>{notice.title}</div>
        <div className={s.panelMsg}>{notice.description}</div>
      </div>
    </div>
  );
}
