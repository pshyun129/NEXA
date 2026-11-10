import React from 'react';

// 스타일·포털의 DOM 구현은 검증하지 않는다. 실제 휴가 컴포넌트와 Query/이벤트 처리는 그대로 실행한다.
const element = (name) => ({ children, ...props }) => React.createElement(name, props, children);
export const Alert = ({ message, description, action, ...props }) =>
  React.createElement('alert', { ...props, message }, message, description, action);
export const Button = element('button');
export const Select = element('select');
export const Segmented = element('segmented');
export const Skeleton = element('skeleton');
export const Input = { TextArea: element('textarea') };
export const Card = element('card');
export const Kbd = element('kbd');
export const Icon = element('icon');
export const ChevronLeft = element('left');
export const ChevronRight = element('right');
export const X = element('close');
export const StatusBadge = element('badge');
export const ErrorPanel = element('error-panel');
export const BarSegments = element('bar');
export const EmptyState = element('empty-state');
export const AsyncSync = element('async-sync');
export const ConfirmAction = element('confirm');
export const PageHeader = element('page-header');
export const Drawer = element('drawer');
export const Tabs = element('tabs');
export const Table = ({ locale, dataSource, ...props }) => React.createElement('table', props,
  dataSource?.length ? null : locale?.emptyText);
export const modKey = 'Ctrl';
export const useMyId = () => 1;
export const useNotify = () => ({ success() {}, error() {}, info() {} });
