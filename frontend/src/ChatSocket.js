/**
 * WebSocket client with reconnect and at-least-once sending.
 *
 * Every outgoing message sits in `outbox` until the server acks it. On every (re)connect
 * the outbox is replayed with the SAME clientMsgId, and the server's unique
 * (sender_id, client_msg_id) constraint turns that into exactly-once storage. Without
 * this, a connection dropped between "server saved it" and "client got the ack" would
 * either lose the message or duplicate it.
 */
export class ChatSocket {
    constructor({onFrame, onStatus, onOpen, onAuthLost}) {
        Object.assign(this, {onFrame, onStatus, onOpen, onAuthLost})
        this.ws = null
        this.attempt = 0
        this.timer = null
        this.closedByUs = false
        this.outbox = new Map() // clientMsgId -> frame; Map keeps insertion order for replay
    }

    connect() {
        this.onStatus('connecting')
        const proto = location.protocol === 'https:' ? 'wss' : 'ws'
        const ws = new WebSocket(`${proto}://${location.host}/ws`)
        this.ws = ws

        // Every handler checks `this.ws !== ws`: events from a socket we've already replaced
        // (or closed) must not touch the current state. React StrictMode in dev creates and
        // destroys a socket on purpose, so this happens for real.
        ws.onopen = () => {
            if (this.ws !== ws) return
            this.attempt = 0
            this.onStatus('online')
            for (const frame of this.outbox.values()) ws.send(JSON.stringify(frame))
            this.onOpen()
        }

        ws.onmessage = (e) => {
            if (this.ws !== ws) return
            let frame
            try {
                frame = JSON.parse(e.data)
            } catch {
                return
            }
            if ((frame.type === 'ack' || frame.type === 'error') && frame.clientMsgId) {
                this.outbox.delete(frame.clientMsgId)
            }
            this.onFrame(frame)
        }

        ws.onclose = () => {
            if (this.ws !== ws || this.closedByUs) return
            this.onStatus('offline')

            // The handshake is rejected with 401 when the session is gone (e.g. server restart).
            // Retrying would loop forever, so check once and send the user back to login.
            fetch('/api/auth/me')
                .then((r) => {
                    if (r.status === 401 && !this.closedByUs) {
                        this.close()
                        this.onAuthLost()
                    }
                })
                .catch(() => {
                }) // server unreachable: keep retrying below

            // Exponential backoff with jitter, so a server restart isn't hit by every
            // client reconnecting at the same instant.
            const delay = Math.min(15000, 500 * 2 ** this.attempt++) * (0.5 + Math.random() / 2)
            this.timer = setTimeout(() => this.connect(), delay)
        }
    }

    send(to, body) {
        const clientMsgId = crypto.randomUUID()
        const frame = {type: 'send', clientMsgId, to, body}
        this.outbox.set(clientMsgId, frame)
        if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(frame))
        return clientMsgId
    }

    close() {
        this.closedByUs = true
        clearTimeout(this.timer)
        this.ws?.close()
    }
}