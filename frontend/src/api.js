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