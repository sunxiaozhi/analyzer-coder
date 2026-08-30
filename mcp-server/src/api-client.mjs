export class AnalyzerApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'AnalyzerApiError';
    this.status = status;
    this.code = code;
  }
}

export class AnalyzerApiClient {
  constructor({ baseUrl, sessionToken, csrfToken, fetchImpl = fetch }) {
    if (!baseUrl) throw new Error('ANALYZER_API_BASE is required');
    if (!sessionToken) throw new Error('ANALYZER_SESSION_TOKEN is required');
    if (!csrfToken) throw new Error('ANALYZER_CSRF_TOKEN is required');
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.sessionToken = sessionToken;
    this.csrfToken = csrfToken;
    this.fetchImpl = fetchImpl;
  }

  async request(path, init = {}) {
    const method = (init.method ?? 'GET').toUpperCase();
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    headers.set('Cookie', `AC_SESSION=${this.sessionToken}`);
    if (init.body !== undefined) headers.set('Content-Type', 'application/json');
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      headers.set('X-CSRF-Token', this.csrfToken);
    }
    const response = await this.fetchImpl(`${this.baseUrl}${path}`, { ...init, method, headers });
    const payload = await readPayload(response);
    if (!response.ok) {
      throw new AnalyzerApiError(
        response.status,
        payload?.code ?? 'HTTP_ERROR',
        payload?.message ?? `${response.status} ${response.statusText}`,
      );
    }
    return payload;
  }
}

async function readPayload(response) {
  if (response.status === 204 || response.headers.get('content-length') === '0') return null;
  const text = await response.text();
  if (!text) return null;
  try { return JSON.parse(text); }
  catch { throw new AnalyzerApiError(response.status, 'INVALID_JSON', '后端返回了无效 JSON'); }
}

export function clientFromEnvironment(environment = process.env) {
  return new AnalyzerApiClient({
    baseUrl: environment.ANALYZER_API_BASE ?? 'http://127.0.0.1:8080',
    sessionToken: environment.ANALYZER_SESSION_TOKEN,
    csrfToken: environment.ANALYZER_CSRF_TOKEN,
  });
}
