const fallbackApiBase = import.meta.env.PROD
  ? 'https://polaris-api-production-32b7.up.railway.app'
  : 'http://localhost:8080';
const API_BASE = import.meta.env.VITE_API_BASE_URL || fallbackApiBase;
const AUTH_TOKEN_KEY = 'polaris-auth-token';
const AUTH_EVENT = 'polaris-auth-changed';
let authToken = localStorage.getItem(AUTH_TOKEN_KEY) || '';

const emitAuthChange = () => {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(AUTH_EVENT));
  }
};

const shouldClearAuthOnUnauthorized = (path) =>
  path.startsWith('/auth') ||
  path.startsWith('/admin') ||
  path.startsWith('/api/keys') ||
  path.startsWith('/actuator');

const parseBody = async (res) => {
  if (res.status === 204) {
    return null;
  }

  const contentType = res.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    return res.json();
  }

  const text = await res.text();
  if (!text) {
    return null;
  }

  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
};

const request = async (path, options = {}) => {
  const defaultHeaders = {};
  if (authToken) {
    defaultHeaders.Authorization = `Bearer ${authToken}`;
  }

  let response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...options,
      headers: {
        ...defaultHeaders,
        ...options.headers
      }
    });
  } catch (networkError) {
    const error = new Error('Unable to reach backend. Check backend server and CORS origin.');
    error.status = 0;
    error.payload = networkError;
    throw error;
  }

  const payload = await parseBody(response);

  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login' && shouldClearAuthOnUnauthorized(path)) {
      clearAuthToken();
    }

    const message =
      typeof payload === 'string'
        ? payload
        : payload?.error || payload?.message || response.statusText || `HTTP ${response.status}`;
    const error = new Error(message || `Request failed (HTTP ${response.status})`);
    error.status = response.status;
    error.payload = payload;
    throw error;
  }

  return payload;
};

export const getAdminProfile = () => request('/profiles/admin');
export const getHealth = () => request('/actuator/health');

export const getMetricsSummary = () => request('/admin/metrics/summary');
export const getAuditLogs = (limit = 50) => request(`/admin/audit/logs?limit=${limit}`);

export const getMetricValue = async (type, plan, algorithm) => {
  try {
    const result = await request(
      `/actuator/metrics/rate_limit.${type}?tag=plan:${plan}&tag=algorithm:${algorithm}`
    );
    const measurement = result?.measurements?.find((m) => m.statistic === 'COUNT');
    return Math.round(measurement?.value || 0);
  } catch (error) {
    if (error?.status === 404) {
      return 0;
    }
    throw error;
  }
};

export const createApiKey = (planType) => request(`/api/keys?plan=${planType}`, { method: 'POST' });

export const listApiKeys = () => request('/api/keys');

export const getApiKeyById = (id) => request(`/api/keys/${id}`);

export const deactivateApiKey = (id) => request(`/api/keys/${id}`, { method: 'DELETE' });

export const getGlobalStrategy = () => request('/admin/strategy');

export const updateStrategy = ({ strategy, plan }) => {
  const planQuery = plan ? `&plan=${plan}` : '';
  return request(`/admin/strategy?strategy=${strategy}${planQuery}`, { method: 'POST' });
};

export const getStrategyDebug = () => request('/admin/strategy/debug');

export const login = ({ username, password, role }) =>
  request('/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, role })
  });

export const getCurrentUser = () => request('/auth/me');

export const logout = () => request('/auth/logout', { method: 'POST' });

export const getUserProfile = (apiKey) =>
  request('/profiles/user', {
    headers: {
      'X-API-KEY': apiKey
    }
  });

export const callProtectedTest = (apiKey) =>
  request('/api/protected/test', {
    headers: {
      'X-API-KEY': apiKey
    }
  });

export const runServerSimulate = (apiKey, count) =>
  request(`/api/simulate?count=${count}`, {
    method: 'POST',
    headers: { 'X-API-KEY': apiKey }
  });

export const setAuthToken = (token) => {
  authToken = token || '';
  if (authToken) {
    localStorage.setItem(AUTH_TOKEN_KEY, authToken);
  } else {
    localStorage.removeItem(AUTH_TOKEN_KEY);
  }
  emitAuthChange();
};

export const clearAuthToken = () => {
  setAuthToken('');
};

export const getStoredAuthToken = () => authToken;
export const getAuthEventName = () => AUTH_EVENT;

export { request };
