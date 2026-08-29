// k6-roegtest (SPRINTS.md, Sprint 1). Gaten er bevidst en ROEGTEST, ikke en
// lasttest: 2 VU'er i 10s beviser, at stakken svarer korrekt hele vejen igennem
// — ikke at den kan baere spidsen. Taersklerne herunder er derfor sat, saa de
// fanger "stakken er i stykker", ikke "stakken er langsom". E15 tilfoejer det
// rigtige scenarie ("resale-vindue aabner", 100 tx/s med én replika, N-1) som en
// separat gate; den skal ikke presses ind i denne.
//
// Roegtesten gaar hele vejen: LB -> app -> vaertsnavn -> operatoer -> RLS -> skabelon.
//
//   docker run --rm --network host -v "$PWD/k6:/scripts" grafana/k6:2.2.0 run /scripts/smoke.js

import http from 'k6/http';
import { check } from 'k6';

export const options = {
	vus: 2,
	duration: '10s',
	thresholds: {
		http_req_failed: ['rate<0.01'],
		http_req_duration: ['p(95)<500'],
		checks: ['rate>0.99'],
	},
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// Vaertsnavnet afgoer operatoeren. Koeres k6 inde i compose-netvaerket (BASE_URL=http://lb),
// saettes Host eksplicit, saa appen stadig ser "localhost".
const HEADERS = __ENV.HOST_HEADER ? { Host: __ENV.HOST_HEADER } : {};

export default function () {
	const res = http.get(`${BASE_URL}/`, { headers: HEADERS });
	check(res, {
		'status 200': (r) => r.status === 200,
		'operator rendered': (r) => r.body.includes('Dev Operator'),
		// Cache-politikken (PLAN §2.7 punkt 2) testes i JUnit paa app-niveau.
		// Her maales den, som verden ser den: efter load balanceren. En
		// proxy-konfiguration, der stripper eller overskriver Cache-Control,
		// ville ikke blive fanget noget andet sted.
		'not cacheable through LB': (r) =>
			(r.headers['Cache-Control'] || '').includes('no-store'),
		// server_tokens off i nginx.conf. Uden den staar der "nginx/1.29.x",
		// og en scanner behoever ikke gaette version.
		'LB reveals no version': (r) =>
			!/\d/.test(r.headers['Server'] || ''),
	});

	// /actuator ligger paa management-porten og maa ALDRIG kunne naas gennem
	// load balanceren. Det er den eneste vej udefra, saa det er her, det maales.
	const actuator = http.get(`${BASE_URL}/actuator/prometheus`, {
		headers: HEADERS,
		// 401/404 er det forventede svar — de skal ikke taelle som fejlrate.
		responseCallback: http.expectedStatuses(401, 404),
	});
	check(actuator, {
		'prometheus not open through LB': (r) => r.status !== 200,
	});
}
