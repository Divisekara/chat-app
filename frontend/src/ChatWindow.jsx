import {useEffect, useRef, useState} from 'react'

const time = (iso) => new Date(iso).toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'})

export default function ChatWindow({active, me, messages, pending, onSend, onLoadOlder}) {
    const [text, setText] = useState('')
    const bottom = useRef(null)
    const newest = messages.at(-1)?.seq

    // Keyed on the newest seq, not messages.length: prepending older history changes the
    // length too and would yank the view to the bottom.
    useEffect(() => {
        bottom.current?.scrollIntoView()
    }, [newest, pending.length])

    function submit(e) {
        e.preventDefault()
        const t = text.trim()
        if (!t) return
        onSend(t)
        setText('')
    }

    return (
        <div className="chat">
            <div className="head">{active.otherUsername}</div>

            <div className="msgs">
                {messages.length > 0 && messages[0].seq > 1 && (
                    <button className="older" onClick={onLoadOlder}>Load older messages</button>
                )}

                {messages.map((m) => (
                    <div key={m.seq} className={`msg ${m.senderId === me.id ? 'mine' : ''}`}>
                        {m.body}
                        <small>{time(m.createdAt)}</small>
                    </div>
                ))}

                {pending.map((p) => (
                    <div key={p.clientMsgId} className={`msg mine ${p.error ? 'failed' : ''}`}>
                        {p.body}
                        <small>{p.error ? `Failed to send (${p.error})` : 'sending...'}</small>
                    </div>
                ))}

                <div ref={bottom}/>
            </div>

            <form className="compose" onSubmit={submit}>
                <input placeholder="Type a message" value={text} maxLength={4000} autoFocus
                       onChange={(e) => setText(e.target.value)}/>

                <button>Send</button>
            </form>
        </div>
    )
}