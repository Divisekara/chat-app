package com.chat.backend.realtime;

import com.chat.backend.message.MessageService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String OUT = "out";

    // Binding frames to records instead of walking a JsonNode tree keeps this code
    // independent of Jackson's tree API, which changed in Jackson 3.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Incoming(String type, String clientMsgId, String to, String body) {
    }

    public record Ack(String type, UUID clientMsgId, UUID conversationId, long seq) {
    }

    public record Deliver(String type, UUID conversationId, long seq, UUID from, String body, Instant createdAt) {
    }

    public record Err(String type, UUID clientMsgId, String code) {
    }

    private final ConnectionRegistry registry;
    private final MessageService messages;
    private final ObjectMapper json;

    ChatWebSocketHandler(ConnectionRegistry registry, MessageService messages, ObjectMapper json) {
        this.registry = registry;
        this.messages = messages;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // A WebSocketSession is not safe for concurrent sends, and two senders can target
        // the same recipient at once. The decorator serializes writes, and the limits
        // (5s / 512KB) make it cut off a stuck client instead of buffering without bound.
        var out = new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024);
        session.getAttributes().put(OUT, out);
        registry.add(userId(session), out);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var out = (WebSocketSession) session.getAttributes().get(OUT);
        if (out != null) registry.remove(userId(session), out);
    }

    @Override
    protected /*TODO why protected, not public?*/ void handleTextMessage(WebSocketSession session, TextMessage message) {
        UUID me = userId(session);
        var out = (WebSocketSession) session.getAttributes().get(OUT);

        Incoming in;
        try {
            in = json.readValue(message.getPayload(), Incoming.class);
        } catch (Exception e) {
            send(out, new Err("error", null, "bad json"));
            return;
        }

        if ("send".equals(in.type)) {
            handleSend(me, out, in);
        } else {
            send(out, new Err("error", null, "unknown type"));
        }
    }

    private void handleSend(UUID me, WebSocketSession out, Incoming in) {
        UUID clientMsgId = null;

        try {
            clientMsgId = uuid(in.clientMsgId());
            UUID to = uuid(in.to());
            // Never trust the sender: the identity is the authenticated session, not a
            // "from" field in the payload.
            String body = in.body() == null ? null : in.body.strip();

            if (body.isEmpty() || body.length() > 4000 /* todo explain the number*/) {
                throw new IllegalArgumentException("body");
            }
            var saved = messages.send(me, to, clientMsgId, body);

            // Fan out only after the transaction has committed. Delivering inside it could
            // show a message to the recipient that then rolls back.
            if (!saved.duplicate()) {
                var deliver = new Deliver("deliver", saved.conversationId(), saved.seq(), me, body, saved.createdAt());
                registry.get(to).forEach(s -> send(s, deliver));
                // The sender's other tabs/devices need the message too.
                registry.get(me).stream().filter(s -> s != out).forEach(s -> send(s, deliver));
            }

            // A duplicate (retry) only gets the ack again, so the recipient never sees it twice.
            send(out, new Ack("ack", clientMsgId, saved.conversationId(), saved.seq()));

        } catch (MessageService.RecipientNotFound e) {
            send(out, new Err("error", clientMsgId, "recipient_not_found"));
        } catch (IllegalArgumentException e) {
            send(out, new Err("error", clientMsgId, "bad_request"));
        } catch (Exception e) {
            log.error("send failed", e);
            send(out, new Err("error", clientMsgId, "server_error"));
        }
    }

    private void send(WebSocketSession target, Object frame) {
        try {
            target.sendMessage(
                    new TextMessage(json.writeValueAsString(frame))
            );
        } catch (Exception e) {
            // One dead or slow recipient must not break the sender's request. The message
            // is already stored, and the client will fetch it on reconnect/sync.
            log.warn("send to session {} failed: {}", target.getId(), e.toString());
        }
    }

    // UUID.fromString(null) throws NullPointerException, which would be reported as a
    // server error instead of a bad request.
    private static UUID uuid(String s) {
        if (s == null) throw new IllegalArgumentException("Missing id");
        return UUID.fromString(s);
    }

    private UUID userId(WebSocketSession session) {
        return UUID.fromString(session.getPrincipal().getName());
    }
}
