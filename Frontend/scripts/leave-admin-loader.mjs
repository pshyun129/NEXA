process.env.LEAVE_ADMIN_REAL_AUTH = '1';
const storage = () => {
  const values = new Map();
  return { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key), clear: () => values.clear() };
};
globalThis.localStorage = storage();
globalThis.sessionStorage = storage();
await import('./leave-qa-loader.mjs');
