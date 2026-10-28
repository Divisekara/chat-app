import {useState} from "react"
import {api} from "./api.js"

export default function Login({onLogin}) {
    const [mode, setMode] = useState('login') // mode - register | login | logout
    const [username, setUsername] = useState('')
    const [password, setPassword] = useState('')
    const [error, setError] = useState('')
    const [busy, setBusy] = useState(false)

    async function submit(e) {
        e.preventDefault()
        setError('')
        setBusy(true)
        try {
            onLogin(await api(`/api/auth/${mode}`, {method: 'POST', body: {username, password}}))
        } catch (err) {
            setError(
                err.status === 401 ? 'Wrong username or password'
                    : err.status === 409 ? 'Username already taken'
                        : err.status === 400 ? 'Username: 3-30 letters, digits or _. Password: 8-72 characters'
                            : ''
            )
        } finally {
            setBusy(false) // await is over
        }
    }

    return (
        <div className="center">
            <form className="card" onSubmit={submit}>
                <h1>{mode === 'login' ? 'Sign in' : 'Create account'}</h1>

                <input placeholder="Username" value={username} autoFocus
                       onChange={(e) => setUsername(e.target.value)}/>

                <input type="password" placeholder="Password" value={password}
                       onChange={(e) => setPassword(e.target.value)}/>

                {error && <p className="error">{error}</p>}

                <button disabled={busy}>{mode === 'login' ? 'Sign in' : 'Register'}</button>

                <button type="button" onClick={() => setMode(mode === 'login' ? 'register' : 'login')}>
                    {mode === 'login' ? 'Need an account? Register' : 'Have an account? Sign in'}
                </button>
            </form>
        </div>
    )

}