http://localhost:8080/actuator/health

// window A
await (await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'alice',password:'password123'})})).json()

// Window B
await (await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'bob',password:'password321'})})).json()
const ws = new WebSocket('ws://localhost:8080/ws'); ws.onmessage = e => console.log('got', e.data)

// window A
const ws = new WebSocket('ws://localhost:8080/ws'); ws.onmessage = e => console.log('got', e.data)
const id = crypto.randomUUID()
ws.send(JSON.stringify({type:'send', clientMsgId:id, to:'<BOB_ID>', body:'hi bob'}))