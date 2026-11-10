import React from 'react';

// 스타일·포털의 DOM 구현은 검증하지 않는다. 실제 휴가 컴포넌트와 Query/이벤트 처리는 그대로 실행한다.
const element = (name) => ({ children, ...props }) => React.createElement(name, props, children);
export const Alert = ({ message, description, action, ...props }) =>
  React.createElement('alert', { ...props, message }, message, description, action);
export const Button = element('button');
export const Select = element('select');
export const Segmented = element('segmented');
export const Skeleton = element('skeleton');
export const Input = Object.assign(element('input'), { TextArea: element('textarea') });
export const InputNumber = element('input-number');
export const Switch = element('switch');
export const Checkbox = element('checkbox');
export const DatePicker = element('date-picker');
export const Space = element('space');
export const Tag = element('tag');
export const DotChip = element('dot-chip');
export const CalendarDays = element('calendar');
export const House = element('house');
export const CalendarClock = element('clock');
export const FileSignature = element('signature');
export const Wallet = element('wallet');
export const Network = element('network');
export const DatabaseZap = element('database');
export const Users = element('users');
export const Modal = ({ children, footer, ...props }) => React.createElement('modal', props, children, footer);
export const FullPageNotice = element('full-page-notice');
export const FullPageError = element('full-page-error');
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

// Form의 DOM/검증 엔진은 대체한다. onFinish 이후 DTO·Mutation·캐시·화면 전이는 실제 코드로 검증한다.
export const Form = ({ form, initialValues, children, onFinish, ...props }) => {
  React.useEffect(() => { form.values = { ...initialValues }; form.emit(); }, [form]);
  form.submit = () => onFinish(form.values);
  return React.createElement('form', { ...props, form, onFinish }, children);
};
Form.useForm = () => {
  const [form] = React.useState(() => {
    const listeners = new Set();
    return { values: {}, submit() {}, emit() { for (const listener of listeners) listener(); },
      subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
      setFieldsValue(values) { Object.assign(this.values, values); this.emit(); },
      setFieldValue(key, value) { this.values[key] = value; this.emit(); },
    };
  });
  return [form];
};
Form.useWatch = (key, form) => React.useSyncExternalStore(form.subscribe, () => form.values[key]);
Form.Item = element('form-item');
Form.List = ({ children }) => children([], { add() {}, remove() {} });
Skeleton.Button = element('skeleton-button');
