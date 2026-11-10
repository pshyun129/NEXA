// Node 기본 테스트 러너에서 프로젝트의 TS/별칭을 읽는다. DOM이 필요한 UI만 테스트 대역으로 바꾼다.
import { registerHooks } from 'node:module';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';

const events = new EventTarget();
globalThis.window = Object.assign(globalThis, {
  location: new URL('http://localhost/'),
  addEventListener: events.addEventListener.bind(events),
  removeEventListener: events.removeEventListener.bind(events),
});
globalThis.document = { hidden: false, visibilityState: 'visible' };
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const source = new URL('../src/', import.meta.url);
const ui = new URL('./leave-qa-ui.mjs', import.meta.url).href;

registerHooks({
  resolve(specifier, context, next) {
    if (specifier === 'antd' || specifier.startsWith('@/ui/') || (specifier === '@/auth/session' && process.env.LEAVE_ADMIN_REAL_AUTH !== '1')) {
      return { url: ui, shortCircuit: true };
    }
    if ((specifier.startsWith('dayjs/plugin/') || specifier.startsWith('dayjs/locale/')) && !specifier.endsWith('.js')) return next(`${specifier}.js`, context);
    if (specifier.endsWith('.css')) return { url: 'leave-qa:css', shortCircuit: true };
    let candidate;
    if (specifier.startsWith('@/')) candidate = new URL(specifier.slice(2), source);
    else if (specifier.startsWith('.') && context.parentURL?.startsWith(source.href)) candidate = new URL(specifier, context.parentURL);
    if (candidate) {
      for (const suffix of ['', '.ts', '.tsx', '/index.ts']) {
        const path = fileURLToPath(candidate) + suffix;
        if (existsSync(path) && /\.(ts|tsx)$/.test(path)) return { url: pathToFileURL(path).href, shortCircuit: true };
      }
    }
    return next(specifier, context);
  },
  load(url, context, next) {
    if (url === 'leave-qa:css') return { format: 'module', source: 'export default {};', shortCircuit: true };
    if (url.startsWith(source.href) && /\.(ts|tsx)$/.test(url)) {
      const output = ts.transpileModule(readFileSync(fileURLToPath(url), 'utf8'), {
        fileName: fileURLToPath(url),
        compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX },
      });
      return { format: 'module', source: output.outputText, shortCircuit: true };
    }
    return next(url, context);
  },
});
