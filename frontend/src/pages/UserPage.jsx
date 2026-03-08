import React, { useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { runServerSimulate, getUserProfile } from '../api/client';
import { getPanelId, getTabId, Tabs } from '../components/ui/tabs';
import { useToast } from '../components/ui/toast';
import './user.css';
import EndpointHint from '../components/ui/endpoint-hint';

const APP_TABS = ['API Key Login', 'User Dashboard', 'Request Simulator'];
const SESSION_ERROR_MESSAGES = new Set(['Authentication required', 'Access denied']);

const UserPage = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const currentTabFromQuery = searchParams.get('tab');
  const initialTab = APP_TABS.includes(currentTabFromQuery) ? currentTabFromQuery : 'API Key Login';
  const [activeTab, setActiveTab] = useState(initialTab);
  const [apiKeyInput, setApiKeyInput] = useState('');
  const [sessionApiKey, setSessionApiKey] = useState('');
  const [profile, setProfile] = useState(null);
  const [requestCount, setRequestCount] = useState('20');
  const [logs, setLogs] = useState([]);
  const [summary, setSummary] = useState({ total: 0, allowed: 0, blocked: 0, unauthorized: 0, otherErrors: 0 });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const simulationLockRef = useRef(false);
  const { pushToast } = useToast();

  const isLoggedIn = Boolean(sessionApiKey && profile);

  const verifyAndContinue = async () => {
    const key = apiKeyInput.trim();
    if (!key) {
      setError('API key is required');
      return;
    }

    setBusy(true);
    setError(null);

    try {
      const userProfile = await getUserProfile(key);
      setSessionApiKey(key);
      setProfile(userProfile);
      onTabChange('User Dashboard');
      pushToast({ title: 'API key verified', description: `Plan ${userProfile.planType}`, variant: 'success' });
    } catch (err) {
      setSessionApiKey('');
      setProfile(null);
      if (SESSION_ERROR_MESSAGES.has(err.message)) {
        setError('Session expired or unauthorized role. Please sign in again.');
      } else if (err.status === 401 || err.status === 403) {
        setError('ERROR: Invalid or inactive API key');
      } else {
        setError(err.message);
      }
    } finally {
      setBusy(false);
    }
  };

  const onTabChange = (tab) => {
    setActiveTab(tab);
    const nextParams = new URLSearchParams(searchParams);
    nextParams.set('tab', tab);
    setSearchParams(nextParams, { replace: true });
  };

  React.useEffect(() => {
    if (currentTabFromQuery && APP_TABS.includes(currentTabFromQuery) && currentTabFromQuery !== activeTab) {
      setActiveTab(currentTabFromQuery);
    }
  }, [activeTab, currentTabFromQuery]);

  const refreshProfile = async () => {
    if (!sessionApiKey) {
      return;
    }

    setBusy(true);
    setError(null);

    try {
      const userProfile = await getUserProfile(sessionApiKey);
      setProfile(userProfile);
    } catch (err) {
      if (SESSION_ERROR_MESSAGES.has(err.message)) {
        setError('Session expired or unauthorized role. Please sign in again.');
      } else if (err.status === 401 || err.status === 403) {
        setError('ERROR: Invalid or inactive API key');
      } else {
        setError(err.message);
      }
    } finally {
      setBusy(false);
    }
  };

  const executeSimulation = async () => {
    if (simulationLockRef.current) {
      return;
    }

    if (!sessionApiKey) {
      setError('Log in with API key first');
      return;
    }

    const normalizedRequestCount = String(requestCount).trim();
    if (!/^\d+$/.test(normalizedRequestCount)) {
      setError('Request count must be a positive integer');
      return;
    }

    const total = Number(normalizedRequestCount);
    if (!Number.isInteger(total) || total <= 0) {
      setError('Request count must be a positive integer');
      return;
    }

    if (total > 5000) {
      setError('Request count must be 5000 or less per run');
      return;
    }

    simulationLockRef.current = true;
    setBusy(true);
    setError(null);
    setLogs([]);
    setSummary({ total, allowed: 0, blocked: 0, unauthorized: 0, otherErrors: 0 });

    try {
      const result = await runServerSimulate(sessionApiKey, total);

      const { allowed, blocked, unauthorized, durationMs } = result;

      setSummary({ total, allowed, blocked, unauthorized, otherErrors: 0 });

      const nextLogs = [`Server fired ${total} requests concurrently in ${durationMs}ms`];
      if (allowed > 0) nextLogs.push(`${allowed} × 200 OK`);
      if (blocked > 0) nextLogs.push(`${blocked} × 429 TOO MANY REQUESTS`);
      if (unauthorized > 0) nextLogs.push(`${unauthorized} × 403 FORBIDDEN`);
      setLogs(nextLogs);

      pushToast({
        title: 'Simulation complete',
        description: `Allowed ${allowed}, Blocked ${blocked}, Unauthorized ${unauthorized}`,
        variant: blocked > 0 ? 'warning' : 'success'
      });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
      simulationLockRef.current = false;
    }
  };

  const createdAtLabel = useMemo(() => {
    if (!profile?.createdAt) {
      return '-';
    }

    const date = new Date(profile.createdAt);
    if (Number.isNaN(date.getTime())) {
      return profile.createdAt;
    }

    return date.toISOString();
  }, [profile]);

  return (
    <section className="user-page">
      <h1>USER ACCESS PORTAL</h1>
      <p className="page-summary">
        Authenticate with your API key, inspect the active strategy assigned to your plan, and execute real protected requests to observe enforcement.
      </p>

      <Tabs items={APP_TABS} value={activeTab} onChange={onTabChange} ariaLabel="User sections" idPrefix="user-sections" />

      {error && <p className="banner error">{error}</p>}

      {activeTab === 'API Key Login' && (
        <article
          className="panel login-panel"
          role="tabpanel"
          id={getPanelId('user-sections', 'API Key Login')}
          aria-labelledby={getTabId('user-sections', 'API Key Login')}
        >
          <h2>API Key Login</h2>
          <p className="table-title">
            <EndpointHint method="GET" path="/profiles/user" detail="requires X-API-KEY header" />
          </p>
          <div className="onboarding-note">
            <p>No API key yet?</p>
            <ol>
              <li>Ask an admin to open <strong>Admin → API Keys</strong>.</li>
              <li>Generate a FREE or PRO key.</li>
              <li>Paste that key here to continue.</li>
            </ol>
          </div>
          <label htmlFor="api-key-input">Enter API Key</label>
          <input
            id="api-key-input"
            value={apiKeyInput}
            onChange={(event) => setApiKeyInput(event.target.value)}
            placeholder="sk_live_83ks9x..."
          />
          <button type="button" onClick={verifyAndContinue} disabled={busy}>
            {busy ? 'Verifying...' : 'Verify & Continue'}
          </button>
        </article>
      )}

      {activeTab === 'User Dashboard' && (
        <article
          className="panel dashboard-panel"
          role="tabpanel"
          id={getPanelId('user-sections', 'User Dashboard')}
          aria-labelledby={getTabId('user-sections', 'User Dashboard')}
        >
          <h2>User Dashboard</h2>
          <p className="table-title">
            <EndpointHint method="GET" path="/profiles/user" />
          </p>
          {!isLoggedIn && <p className="muted">Log in with an API key to load dashboard data.</p>}

          {isLoggedIn && (
            <>
              <div className="kv-list">
                <div>
                  <span>ID</span>
                  <strong>{profile.id}</strong>
                </div>
                <div>
                  <span>Plan Type</span>
                  <strong>{profile.planType}</strong>
                </div>
                <div>
                  <span>Active</span>
                  <strong>{String(profile.active)}</strong>
                </div>
                <div>
                  <span>Created At</span>
                  <strong>{createdAtLabel}</strong>
                </div>
              </div>

              <h3 className="strategy-heading">Governing Rate Limit Strategy</h3>
              <p className="value-block">{profile.currentStrategy}</p>

              {!profile.active && <div className="inactive-warning">This API key is inactive.</div>}

              <button type="button" onClick={refreshProfile} disabled={busy}>
                Refresh Profile
              </button>
            </>
          )}
        </article>
      )}

      {activeTab === 'Request Simulator' && (
        <article
          className="panel simulator-panel"
          role="tabpanel"
          id={getPanelId('user-sections', 'Request Simulator')}
          aria-labelledby={getTabId('user-sections', 'Request Simulator')}
        >
          <h2>Request Simulator</h2>
          <p className="table-title">
            <EndpointHint method="GET" path="/api/protected/test" detail="repeatable simulation" />
          </p>
          {!sessionApiKey && <p className="muted">Log in with an API key first. Ask admin to generate one from Admin → API Keys.</p>}

          <div className="inline-form">
            <label htmlFor="request-count">Number of Requests</label>
            <input
              id="request-count"
              type="number"
              min="1"
              max="5000"
              step="1"
              value={requestCount}
              onChange={(event) => setRequestCount(event.target.value)}
            />
            <button type="button" onClick={executeSimulation} disabled={busy || !sessionApiKey}>
              {busy ? 'Executing...' : 'Execute Test'}
            </button>
          </div>

          <section className="summary-grid" aria-label="Simulation metrics">
            <article className="metric-card metric-card--total">
              <p className="metric-label">Total Sent</p>
              <strong className="metric-value">{summary.total}</strong>
              <span className="metric-badge metric-badge--total">Requests</span>
            </article>

            <article className="metric-card metric-card--allowed">
              <p className="metric-label">Allowed</p>
              <strong className="metric-value">{summary.allowed}</strong>
              <span className="metric-badge metric-badge--allowed">200 OK</span>
            </article>

            <article className="metric-card metric-card--blocked">
              <p className="metric-label">Blocked</p>
              <strong className="metric-value">{summary.blocked}</strong>
              <span className="metric-badge metric-badge--blocked">429 Too Many Requests</span>
            </article>

            <article className="metric-card metric-card--unauthorized">
              <p className="metric-label">Unauthorized</p>
              <strong className="metric-value">{summary.unauthorized}</strong>
              <span className="metric-badge metric-badge--unauthorized">401/403 Auth Errors</span>
            </article>
          </section>

          {summary.otherErrors > 0 && <p className="other-error-note">Other errors: {summary.otherErrors}</p>}

          <h3>Response Log</h3>
          <div className="log-list">
            <p className="log-list-header">Latest Responses</p>
            {logs.length === 0 && <p className="muted">No requests executed yet.</p>}
            {logs.map((entry, index) => (
              <p key={`${entry}-${index}`}>{entry}</p>
            ))}
          </div>
        </article>
      )}
    </section>
  );
};

export default UserPage;
