/**
 * Every call to our own backend goes through here.
 *
 * URLs are relative ("/api/...") on purpose: the browser talks to a single origin (the
 * Vite proxy in dev, nginx in Docker). That is why the session cookie is sent
 * automatically and no CORS configuration is needed. There is no token handling either:
 * the session cookie is HttpOnly, so JavaScript can't read it and doesn't need to.
 */
export async function api(path, {method = 'GET', body} = {}) {
    const res = await fetch(path, {
        method,
        headers: body ? {'Content-Type': 'application/json'} : undefined,
        body: body ? JSON.stringify(body) : undefined,
    })

    if (!res.ok) {
        const err = new Error(`HTTP ${res.status}`)
        err.status = res.status
        throw err
    }

    return res.status === 204 ? null : res.json()
}