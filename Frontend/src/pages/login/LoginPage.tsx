import { useEffect, useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { authApi } from '@/auth/authApi';
import { useSession } from '@/auth/session';
import { isApiError } from '@/api/ApiError';
import { Icon } from '@/ui/Icon';
import { Hexagon } from '@/ui/icons';
import s from './login.module.css';

export const EXPIRED_FLAG = 'nx.sessionExpired';
const LOCK_SECONDS = 15 * 60;

interface FormValues { email: string; pass: string }

function useCountdown(until: number | null) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!until) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [until]);
  if (!until) return null;
  const left = Math.max(0, Math.ceil((until - now) / 1000));
  return left > 0 ? `${String(Math.floor(left / 60)).padStart(2, '0')}:${String(left % 60).padStart(2, '0')}` : null;
}

export function LoginPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { status, markLoggedIn } = useSession();
  const [form] = Form.useForm<FormValues>();
  const [error, setError] = useState<string | null>(null);
  const [lockedUntil, setLockedUntil] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [expired] = useState(() => {
    try {
      const v = sessionStorage.getItem(EXPIRED_FLAG) === '1';
      sessionStorage.removeItem(EXPIRED_FLAG);
      return v;
    } catch { return false; }
  });
  const countdown = useCountdown(lockedUntil);
  const next = params.get('next');
  const target = next && next.startsWith('/') && !next.startsWith('//') ? next : '/';

  if (status === 'authenticated') return <Navigate to={target} replace />;

  const submit = async ({ email, pass }: FormValues) => {
    setBusy(true);
    setError(null);
    try {
      await authApi.login(email.trim(), pass);
      // 신원·소속은 SessionGate가 /employees/me로 확인한다
      markLoggedIn();
      navigate(target, { replace: true });
    } catch (e) {
      if (!isApiError(e)) {
        setError('서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.');
      } else if (e.code === 'AUTH_010') {
        // 서버가 준 남은 초로 센다(lockedUntil 절대 시각보다 PC 시계 차이에 강하다). 없으면 규정상 최대 15분
        const left = e.detailNumber('retryAfterSeconds');
        setLockedUntil(Date.now() + (left ?? LOCK_SECONDS) * 1000);
        setError(left != null ? '계정이 잠겼습니다. 남은 시간이 지나면 다시 시도해 주세요.' : '계정이 잠겼습니다. 최대 15분 후 다시 시도해 주세요.');
      } else if (e.code === 'AUTH_005') {
        // remainingAttempts: 잠기기 전까지 남은 시도(4 → 0). 0이면 이번 실패로 잠긴 것이다
        const remaining = e.detailNumber('remainingAttempts');
        setError(remaining === 0
          ? '비밀번호를 5회 틀려 계정이 15분 동안 잠겼습니다.'
          : remaining != null
            ? `이메일 또는 비밀번호가 올바르지 않습니다. ${remaining}회 더 틀리면 15분 동안 잠깁니다.`
            : '이메일 또는 비밀번호가 올바르지 않습니다. 5회 틀리면 15분 동안 잠깁니다.');
      } else if (e.code === 'AUTH_006') {
        setError('퇴직 처리된 계정입니다. 인사 담당자에게 문의해 주세요.');
      } else if (e.code === 'COMMON_001') {
        form.setFields(e.fieldErrors().map((f) => ({ name: f.name as keyof FormValues, errors: f.errors })));
      } else {
        setError(e.message);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={s.page}>
      <div className={s.box}>
        <div className={s.brand}>
          <Icon as={Hexagon} size={24} className={s.mark} />
          <span>NEXA</span>
        </div>
        <h1 className={s.title}>로그인</h1>
        <p className={s.desc}>회사 이메일로 로그인합니다.</p>

        {expired && !error && (
          <Alert type="info" showIcon className={s.alert}
                 message="세션이 만료되었습니다(다른 기기 로그인 포함). 다시 로그인해 주세요." />
        )}
        {error && (
          <Alert type="error" showIcon className={s.alert}
                 message={countdown ? `${error} (${countdown})` : error} />
        )}

        <Form<FormValues> form={form} layout="vertical" requiredMark onFinish={submit} disabled={busy}>
          <Form.Item name="email" label="이메일" rules={[{ required: true, message: '이메일을 입력해 주세요.' },
            { type: 'email', message: '이메일 형식이 아닙니다. 예: user1@local.dev' }]} validateTrigger="onBlur">
            <Input autoComplete="username" placeholder="예: user1@local.dev" autoFocus />
          </Form.Item>
          <Form.Item name="pass" label="비밀번호" rules={[{ required: true, message: '비밀번호를 입력해 주세요.' }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={busy} disabled={!!countdown}>
            로그인
          </Button>
        </Form>
      </div>
    </div>
  );
}
