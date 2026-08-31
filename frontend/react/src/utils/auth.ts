export function authHeaders(): { Authorization: string } | Record<string, never> {
  const token = localStorage.getItem('token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function getStoredUser(): { role?: string } | null {
  const raw = localStorage.getItem('user');
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function isAdminUser(): boolean {
  return getStoredUser()?.role === 'ADMIN';
}
