import http from 'k6/http';
import { check, sleep } from 'k6';

// 在线问答负载（spec §4.5）：普通 POST 读完整 SSE 流（连接关闭 = answer.completed/failed），
// 全链路时延即 http_req_duration；首 token 时延从 Prometheus veridex.generation.first_token 查询
// （见 run-matrix.sh 的 prom_first_token）。
// 环境变量: BASE_URL(默认 http://127.0.0.1:8090), KB_IDS(逗号分隔), VUS, DURATION,
//           USERNAME(默认 admin), PASSWORD(默认 veridex)
//
// 登录/CSRF（实测确认）：
//   - k6 的 cookie jar 是 per-VU 的，setup() 的 jar 不共享给 VU → 每个 VU 在 default() 内
//     自行登录一次（模块级变量 per-VU 生效）。
//   - Spring Security 登录成功会轮换 CSRF token 并回 Set-Cookie：旧 XSRF 删除 cookie
//     （Expires=1970）+ 新 XSRF + 新 JSESSIONID。
//   - 实测发现 k6 2.2.0 cookie jar 的 bug：响应里出现 Expires 过期的删除 cookie 后，
//     整个 jar 会在下一个迭代被清空（下个请求不再带任何 Cookie → 401/302）。
//     因此本脚本不从 jar 自动带 cookie，改为登录后从 jar 读出 XSRF/JSESSIONID 并
//     在 QA 请求上用显式 Cookie header 发送（已验证 4/4 全过、服务端不轮换）。

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8090';
const KB_IDS = (__ENV.KB_IDS || '').split(',').filter(Boolean);
const USERNAME = __ENV.USERNAME || 'admin';
const PASSWORD = __ENV.PASSWORD || 'veridex';
const QUESTIONS = [
  '请假需要提前几个工作日申请',
  '差旅报销的流程是什么',
  '保密制度对文档分类的要求',
  '考勤异常如何处理',
  '新员工培训时长规定',
];

export const options = {
  // 全链路时延分位（spec §4.5 要求 P50/P95/P99）：显式声明 summaryTrendStats，
  // 否则 k6 默认只导出 avg/min/med/max + p(90)/p(95)，--summary-export 里不会有 p(99)
  // （Task 8 容量报告引用全链路 P99，review I-1）。
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    qa: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: Number(__ENV.VUS || 5) },
        { duration: __ENV.DURATION || '5m', target: Number(__ENV.VUS || 5) },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
  },
};

// 每个 VU 各自的登录会话（k6 模块级变量为 per-VU 实例）
let session = null;

export function setup() {
  if (KB_IDS.length === 0) {
    throw new Error('KB_IDS 为空：请传入 -e KB_IDS=<逗号分隔的 knowledge base id>');
  }
  // 探活：失败即快速失败，避免压测打空
  const probe = http.get(`${BASE_URL}/api/auth/csrf`, { timeout: '10s' });
  if (probe.status >= 400) {
    throw new Error(`backend 不可达（${BASE_URL}，HTTP ${probe.status}）：请先起 compose 栈`);
  }
  return { kbIds: KB_IDS };
}

function ensureSession() {
  if (session) {
    return session;
  }
  const jar = http.cookieJar();
  jar.clear(BASE_URL);
  // 1) 登录前 GET /csrf 种初始 XSRF-TOKEN cookie
  check(http.get(`${BASE_URL}/api/auth/csrf`), { 'csrf ok': (r) => r.status === 200 });
  // 2) 表单登录建立会话（login 在 SecurityConfig 的 CSRF ignore 列表内）
  const login = http.post(`${BASE_URL}/api/auth/login?username=${USERNAME}&password=${PASSWORD}`);
  check(login, { 'login ok': (r) => r.status < 300 });
  // 3) 从 jar 读出登录后的 XSRF-TOKEN 与 JSESSIONID（登录响应已轮换/新建）
  const jarCookies = jar.cookiesForURL(BASE_URL);
  const xsrf = jarCookies['XSRF-TOKEN'];
  const jsid = jarCookies['JSESSIONID'];
  if (!xsrf || !jsid) {
    throw new Error(`登录后 cookie 缺失（jar=${JSON.stringify(jarCookies)}），登录/CSRF 流程失败`);
  }
  session = { xsrf: xsrf[0] || xsrf, jsid: jsid[0] || jsid };
  // 4) 清空 jar：之后 QA 请求显式带 Cookie header（规避 k6 jar 删除 cookie bug）
  jar.clear(BASE_URL);
  return session;
}

export default function (data) {
  const { xsrf, jsid } = ensureSession();
  const question = QUESTIONS[Math.floor(Math.random() * QUESTIONS.length)];
  // k6 http.post 会读完整响应体——SSE 流在服务端 complete 后关闭，duration 即全链路时延
  const resp = http.post(
    `${BASE_URL}/api/qa/ask`,
    JSON.stringify({ question, knowledgeBaseIds: data.kbIds, conversationId: null }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': xsrf,
        'Cookie': `XSRF-TOKEN=${xsrf}; JSESSIONID=${jsid}`,
      },
      timeout: '120s',
    }
  );
  check(resp, {
    'sse ok': (r) => r.status === 200,
    'answer delivered': (r) => r.body && (r.body.includes('answer.completed')
        || r.body.includes('answer.refused')),
  });
  sleep(1);
}
