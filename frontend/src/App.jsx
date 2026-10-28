import {useEffect, useState} from "react";
import {api} from "./api.js"
import Login from "./Login.jsx"
import ChatApp from "./ChatApp.jsx"

export default function App() {
    const [me, setMe] = useState(undefined) // undefined = checking, null = logged out

    useEffect(() => {
        api('/api/auth/me').then(setMe).catch(() => setMe(null))
    }, []);

    async function logout() {
        await api('/api/auth/logout', {method: 'POST'}).catch(() => {
        })
        setMe(null)
    }

    if (me === undefined) return <div className="center">Loading...</div>
    if (me === null) return <Login onLogin={setMe}/>

    return <ChatApp me={me} onLogout={logout} onSessionExpired={() => setMe(null)}/> // FIXME
}