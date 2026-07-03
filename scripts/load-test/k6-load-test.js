import http from 'k6/http';
import { check, sleep } from 'k6';

// CẤU HÌNH CHIẾN DỊCH TẤN CÔNG (Load Test Plan)
export const options = {
  stages: [
    { duration: '1m', target: 500 },  // Ramp-up: 500 Virtual Users (VU) trong 1 phút
    { duration: '3m', target: 2000 }, // Peak-load: Duy trì 2000 VU (Tương đương 10k-20k TPS tùy latency)
    { duration: '1m', target: 0 },    // Ramp-down
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],  // Tỷ lệ lỗi < 1%
    http_req_duration: ['p(95)<200'], // Latency 95% < 200ms
  },
};

const KONG_GATEWAY_URL = 'http://localhost:8000/api/loans';

export default function () {
  const sagaId = `SAGA-${__VU}-${__ITER}`;
  const loanId = `LOAN-${__VU}-${__ITER}`;

  const payload = JSON.stringify({
    loanApplicationId: loanId,
    sagaId: sagaId,
    customerId: `USER-${Math.floor(Math.random() * 100000)}`,
    amount: Math.floor(Math.random() * 500) * 1000000,
    productType: 'PERSONAL_LOAN',
    simulateKycFail: false,
    simulateCreditReject: false,
    simulateDisbursementFail: false
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-Trace-Id': `TRACE-${sagaId}`
    },
  };

  const res = http.post(KONG_GATEWAY_URL, payload, params);

  check(res, {
    'is status 202': (r) => r.status === 202,
    'is request accepted': (r) => r.body.includes('ACCEPTED'),
  });

  sleep(0.1); // Delay giữa các request để mô phỏng hành vi người dùng thực
}
