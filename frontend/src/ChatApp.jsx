import {useCallback, useEffect, useRef, useState} from 'react'
import {api} from './api'
import {ChatSocket} from './ChatSocket'
import ChatWindow from './ChatWindow'

export default function ChatApp({me, onLogout, onSessionExpired}) {
    const [status, setStatus] = useState('connecting')
    const [conversations, setConversations] = useState([])
    const [messages, setMessages] = useState({}) // conversationId -> messages sorted by seq
    const [pending, setPending] = useState([]) // sent, not acked yet
    const [active, setActive] = useState(null) // { conversationId | null, otherUserId, otherUsername }
    const [query, setQuery] = useState('')
    const [results, setResults] = useState([])

    // Socket callbacks outlive renders and would read stale state, so what they need is
    // mirrored in refs, and they call through `handlers`, which is refreshed every render.
    const socketRef = useRef(null)
    const activeRef = useRef(null)
    const pendingRef = useRef([])
    const lastSeq = useRef(new Map()) // conversationId -> highest seq received
    const handlers = useRef({})

    function selectActive(a) {
        activeRef.current = a
        setActive(a)
    }

    function updatePending(fn) {
        pendingRef.current = fn(pendingRef.current)
        setPending(pendingRef.current)
    }

    const loadConversations = useCallback(
        () => api('/api/conversations').then(setConversations).catch(() => {
        }),
        []
    )

    // Merging by seq makes every path idempotent: live push, history page and sync can
    // overlap or arrive twice without producing duplicates.
    const addMessages = useCallback((convId, incoming) => {
        if (!incoming.length) return
        setMessages((prev) => {
            const bySeq = new Map((prev[convId] ?? []).map((m) => [m.seq, m]))
            incoming.forEach((m) => bySeq.set(m.seq, m))
            return {...prev, [convId]: [...bySeq.values()].sort((a, b) => a.seq - b.seq)}
        })
        const max = Math.max(...incoming.map((m) => m.seq))
        lastSeq.current.set(convId, Math.max(lastSeq.current.get(convId) ?? 0, max))
    }, [])

    // Pull everything after the last seq we know, in pages, until caught up.
    const syncConversation = useCallback(
        async (convId) => {
            let after = lastSeq.current.get(convId)
            if (after === undefined) return
            for (; ;) {
                const page = await api(`/api/conversations/${convId}/messages?afterSeq=${after}&limit=100`)
                if (!page.length) break
                addMessages(convId, page)
                after = page[page.length - 1].seq
                if (page.length < 100) break
            }
        },
        [addMessages]
    )

    // A live message whose seq skips ahead means a push was lost; seq is gapless per
    // conversation, so the gap is detectable and we fetch what's missing.
    function receiveLive(convId, msg) {
        const known = lastSeq.current.get(convId)
        if (known !== undefined && msg.seq > known + 1) syncConversation(convId)
        addMessages(convId, [msg])
    }

    useEffect(() => {
        handlers.current = {
            frame(f) {
                if (f.type === 'deliver') {
                    receiveLive(f.conversationId, {
                        seq: f.seq, senderId: f.from, body: f.body, createdAt: f.createdAt,
                    })
                    const a = activeRef.current
                    if (a && !a.conversationId && a.otherUserId === f.from) {
                        selectActive({...a, conversationId: f.conversationId})
                    }
                    loadConversations()
                } else if (f.type === 'ack') {
                    const p = pendingRef.current.find((x) => x.clientMsgId === f.clientMsgId)
                    if (!p) return // repeat ack for a resent message we've already confirmed
                    updatePending((list) => list.filter((x) => x !== p))
                    receiveLive(f.conversationId, {
                        seq: f.seq, senderId: me.id, body: p.body, createdAt: p.createdAt,
                        clientMsgId: f.clientMsgId,
                    })
                    const a = activeRef.current
                    if (a && !a.conversationId && a.otherUserId === p.to) {
                        selectActive({...a, conversationId: f.conversationId})
                    }
                    loadConversations()
                } else if (f.type === 'error') {
                    updatePending((list) =>
                        list.map((x) => (x.clientMsgId === f.clientMsgId ? {...x, error: f.code} : x))
                    )
                }
            },
            open() {
                // After any reconnect, catch up on everything we may have missed while offline.
                for (const id of lastSeq.current.keys()) syncConversation(id)
                loadConversations()
            },
            authLost: onSessionExpired,
        }
    })

    useEffect(() => {
        loadConversations()
        const s = new ChatSocket({
            onStatus: setStatus,
            onFrame: (f) => handlers.current.frame(f),
            onOpen: () => handlers.current.open(),
            onAuthLost: () => handlers.current.authLost(),
        })
        socketRef.current = s
        s.connect()
        return () => s.close()
    }, [loadConversations])

    useEffect(() => {
        const q = query.trim()
        if (!q) return
        const t = setTimeout(
            () => api(`/api/users?q=${encodeURIComponent(q)}`).then(setResults).catch(() => {
            }),
            250
        )
        return () => clearTimeout(t)
    }, [query])

    async function openConversation(c) {
        selectActive(c)
        if (c.conversationId) {
            addMessages(c.conversationId, await api(`/api/conversations/${c.conversationId}/messages`))
        }
    }

    function startWith(user) {
        setQuery('')
        const existing = conversations.find((c) => c.otherUserId === user.id)
        if (existing) {
            openConversation({
                conversationId: existing.id, otherUserId: existing.otherUserId, otherUsername: existing.otherUsername,
            })
        } else {
            selectActive({conversationId: null, otherUserId: user.id, otherUsername: user.username})
        }
    }

    function send(text) {
        const a = activeRef.current
        const clientMsgId = socketRef.current.send(a.otherUserId, text)
        updatePending((list) => [
            ...list,
            {clientMsgId, to: a.otherUserId, body: text, createdAt: new Date().toISOString()},
        ])
    }

    async function loadOlder() {
        const a = activeRef.current
        const first = messages[a.conversationId][0]
        addMessages(a.conversationId, await api(
            `/api/conversations/${a.conversationId}/messages?beforeSeq=${first.seq}`))
    }

    const list = active?.conversationId ? messages[active.conversationId] ?? [] : []
    const mine = active ? pending.filter((p) => p.to === active.otherUserId) : []
    const searching = query.trim() !== ''

    return (
        <div className="app">
            <aside className="sidebar">
                <div className="me">
                    <strong>{me.username}</strong>
                    <span className={`dot ${status}`} title={status}/>
                    <button className="link" onClick={onLogout}>Log out</button>
                </div>
                <input className="search" placeholder="Find a user…" value={query}
                       onChange={(e) => setQuery(e.target.value)}/>
                <ul>
                    {searching
                        ? results.map((u) => (
                            <li key={u.id} onClick={() => startWith(u)}>{u.username}</li>
                        ))
                        : conversations.map((c) => (
                            <li key={c.id}
                                className={active?.conversationId === c.id ? 'sel' : ''}
                                onClick={() => openConversation({
                                    conversationId: c.id, otherUserId: c.otherUserId, otherUsername: c.otherUsername,
                                })}>
                                <b>{c.otherUsername}</b>
                                <small>{c.lastSenderId === me.id ? 'You: ' : ''}{c.lastBody}</small>
                            </li>
                        ))}
                </ul>
            </aside>
            <main>
                {active ? (
                    <ChatWindow key={active.otherUserId} active={active} me={me}
                                messages={list} pending={mine} onSend={send} onLoadOlder={loadOlder}/>
                ) : (
                    <div className="empty">Search for a user to start chatting</div>
                )}
            </main>
        </div>
    )
}