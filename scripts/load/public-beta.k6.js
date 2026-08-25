import http from 'k6/http'
import { check, sleep } from 'k6'
import { Rate, Trend } from 'k6/metrics'

const errors = new Rate('insightops_errors')
const latency = new Trend('insightops_latency', true)
const baseUrl = __ENV.BASE_URL || 'https://insightops.canghaior.com'

export const options = {
  scenarios: {
    public_status: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: Number(__ENV.VUS || 20) },
        { duration: __ENV.HOLD || '3m', target: Number(__ENV.VUS || 20) },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    insightops_errors: ['rate<0.01'],
    insightops_latency: ['p(95)<1500', 'p(99)<3000'],
    http_req_failed: ['rate<0.01'],
  },
}

export default function () {
  const response = http.get(`${baseUrl}/api/v1/public/identity/registration/status`, {
    headers: { 'X-Trace-Id': `capacity-${__VU}-${__ITER}` },
    tags: { endpoint: 'public-registration-status' },
  })
  const ok = check(response, {
    'status is 200': (value) => value.status === 200,
    'safe public payload': (value) => !value.body.includes('secretKey') && !value.body.includes('secretId'),
  })
  errors.add(!ok)
  latency.add(response.timings.duration)
  sleep(0.5 + Math.random())
}
