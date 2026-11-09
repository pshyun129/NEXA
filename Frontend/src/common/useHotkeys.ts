import { useEffect, useRef } from 'react';

/** 입력 중이면 단일 키 단축키를 끈다(설계 7.2) */
export function isTypingTarget(t: EventTarget | null) {
  if (!(t instanceof HTMLElement)) return false;
  if (t.isContentEditable) return true;
  const tag = t.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
  return t.closest('[role="combobox"], [role="textbox"]') != null;
}

/** 확인 모달이 떠 있으면(포커스가 모달 안) 화면 단축키를 쓰지 않는다 */
const inModal = (t: EventTarget | null) => t instanceof HTMLElement && t.closest('.ant-modal') != null;

/**
 * 단일 키 단축키. 키는 KeyboardEvent.key 기준이고 영문자는 대소문자를 가리지 않는다(J/K/A/R/F, '/', '?').
 * Ctrl·⌘·Alt가 눌렸거나 입력 중·모달 안이면 무시한다.
 */
export function useHotkeys(handlers: Record<string, (e: KeyboardEvent) => void>, enabled = true) {
  const ref = useRef(handlers);
  ref.current = handlers;
  useEffect(() => {
    if (!enabled) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey || e.isComposing) return;
      if (isTypingTarget(e.target) || inModal(e.target)) return;
      const key = e.key.length === 1 ? e.key.toLowerCase() : e.key;
      const fn = ref.current[key];
      if (!fn) return;
      e.preventDefault();
      fn(e);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [enabled]);
}
