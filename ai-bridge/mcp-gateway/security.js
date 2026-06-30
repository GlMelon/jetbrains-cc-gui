export function tokenFromRequest(req) {
  const header = req.headers.authorization ?? '';
  return header.startsWith('Bearer ') ? header.slice('Bearer '.length) : '';
}

export function requireToken(req, token) {
  return tokenFromRequest(req) === token;
}

export function redactToken(text, token) {
  if (!token) return text;
  return String(text).split(token).join('[redacted-token]');
}
