import http from 'k6/http';

const RATE = Number(__ENV.RATE || 16000);
const VUS = Number(__ENV.VUS || 4500);
const RAMP_S = Number(__ENV.RAMP_S || 15);
const HOLD_S = Number(__ENV.HOLD_S || 90);

export const options = {
  discardResponseBodies: true,
  summaryTrendStats: ['med', 'p(95)', 'max'],
  scenarios: {
    load: {
      executor: 'ramping-arrival-rate',
      startRate: Math.round(RATE / 4),
      timeUnit: '1s',
      preAllocatedVUs: VUS,
      maxVUs: VUS,
      stages: [
        { target: RATE, duration: `${RAMP_S}s` },
        { target: RATE, duration: `${HOLD_S}s` },
      ],
    },
  },
};

export default function () {
  http.get(`${__ENV.BASE_URL || 'http://mockserver:1080'}/simple`, { timeout: '30s' });
}

export function handleSummary(data) {
  const m = data.metrics;
  const summary = {
    reqs: m.http_reqs ? m.http_reqs.values.count : 0,
    failed: m.http_req_failed ? m.http_req_failed.values.rate : 1,
    p95_ms: m.http_req_duration ? m.http_req_duration.values['p(95)'] : null,
    dropped: m.dropped_iterations ? m.dropped_iterations.values.count : 0,
  };
  return { stdout: `K6_SUMMARY ${JSON.stringify(summary)}\n` };
}
