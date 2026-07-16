package com.bovae.yac.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.UserDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.PresenceStatus;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.MessageRepository;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.PresenceService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import java.lang.reflect.Type;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * WebSocket integration tests for STOMP handlers: ChatMessageHandler, PresenceHandler, TypingHandler.
 *
 * <p>Validates Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6
 *
 * <p>Uses RANDOM_PORT with real WebSocket connections. Authentication is handled via
 * session cookies obtained through HTTP form login. Redis presence state is cleaned
 * in {@code @AfterEach}.
 *
 * <p>Note: {@code @Transactional} is NOT used because RANDOM_PORT runs the server
 * in a separate thread — transactions would not span across.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
class WebSocketIT {

    @LocalServerPort
    private int port;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private PresenceService presenceService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private UserRepository userRepository;

    private User userA;
    private User userB;
    private Room room;

    private final List<WebSocketStompClient> stompClients = new ArrayList<>();
    private final List<StompSession> stompSessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserDto userADto = userService.register("ws-a-" + suffix + "@test.com", "wsa" + suffix, "testpass123");
        userA = userRepository.findById(userADto.id()).orElseThrow();
        UserDto userBDto = userService.register("ws-b-" + suffix + "@test.com", "wsb" + suffix, "testpass123");
        userB = userRepository.findById(userBDto.id()).orElseThrow();
        room = roomService.getRoomById(roomService
                .createRoom("ws-room-" + suffix, "test room", RoomVisibility.PUBLIC, userA)
                .id());
        roomMemberService.joinPublicRoom(room, userB);
    }

    @AfterEach
    void cleanup() {
        for (StompSession session : stompSessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        for (WebSocketStompClient client : stompClients) {
            client.stop();
        }
        stompSessions.clear();
        stompClients.clear();

        presenceService.removeSession(userA.getId(), "it-session");
        presenceService.removeSession(userB.getId(), "it-session");
    }

    // ---- Requirement 12.1: STOMP message to /app/chat.send persists and broadcasts ----

    /**
     * Validates Requirement 12.1: Sending a STOMP message to /app/chat.send persists
     * the message in the database and broadcasts it to /topic/room.{roomId}.
     */
    @Test
    void sendMessage_persistsAndBroadcastsToRoomTopic() throws Exception {
        String sessionCookie = authenticateViaHttp(userA.getEmail(), "testpass123");
        StompSession session = connectStomp(sessionCookie);

        BlockingQueue<Map> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/room." + room.getId(), new QueueFrameHandler<>(received, Map.class));

        Map<String, Object> payload = Map.of("room_id", room.getId().toString(), "content", "Hello via WebSocket!");
        Map message = sendUntilReceived(session, "/app/chat.send", payload, received);
        assertThat(message).isNotNull();
        assertThat(message.get("content")).isEqualTo("Hello via WebSocket!");
        assertThat(message.get("room_id")).isEqualTo(room.getId().toString());

        // Verify message persisted in database
        assertThat(messageRepository.findAll().stream().anyMatch(m -> "Hello via WebSocket!".equals(m.getContent())))
                .isTrue();
    }

    // ---- Requirement 12.2: Heartbeat updates presence in Redis ----

    /**
     * Validates Requirement 12.2: Sending a heartbeat to /app/presence.heartbeat
     * with active=true updates the user's presence status in Redis to ONLINE.
     */
    @Test
    void heartbeat_updatesPresenceInRedis() throws Exception {
        String sessionCookie = authenticateViaHttp(userA.getEmail(), "testpass123");
        StompSession session = connectStomp(sessionCookie);

        // Initially OFFLINE
        assertThat(presenceService.getUserStatus(userA.getId())).isEqualTo(PresenceStatus.OFFLINE);

        Map<String, Object> payload = Map.of("active", true);
        session.send("/app/presence.heartbeat", payload);

        // Await the observable side effect instead of a fixed sleep (R1-53).
        awaitUntil(() -> presenceService.getUserStatus(userA.getId()) == PresenceStatus.ONLINE);
        assertThat(presenceService.getUserStatus(userA.getId())).isEqualTo(PresenceStatus.ONLINE);
    }

    // ---- Requirement 12.3: Typing indicator broadcasts to room events ----

    /**
     * Validates Requirement 12.3: Sending a typing indicator to /app/typing
     * broadcasts a RoomEvent to /topic/room.{roomId}.events.
     */
    @Test
    void typingIndicator_broadcastsToRoomEvents() throws Exception {
        String sessionCookie = authenticateViaHttp(userA.getEmail(), "testpass123");
        StompSession session = connectStomp(sessionCookie);

        BlockingQueue<Map> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/room." + room.getId() + ".events", new QueueFrameHandler<>(received, Map.class));

        Map<String, Object> payload = Map.of("room_id", room.getId().toString());
        Map event = sendUntilReceived(session, "/app/typing", payload, received);
        assertThat(event).isNotNull();
        assertThat(event.get("type")).isEqualTo("TYPING");
        assertThat(event.get("room_id")).isEqualTo(room.getId().toString());
    }

    // ---- Requirement 12.4: @MessageExceptionHandler sends error to /user/queue/errors ----

    /**
     * Validates Requirement 12.4: When message sending fails (e.g., non-existent room),
     * the @MessageExceptionHandler sends an error payload to /user/queue/errors.
     */
    @Test
    void messageExceptionHandler_sendsErrorToUserQueue() throws Exception {
        String sessionCookie = authenticateViaHttp(userA.getEmail(), "testpass123");
        StompSession session = connectStomp(sessionCookie);

        BlockingQueue<Map> errors = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/errors", new QueueFrameHandler<>(errors, Map.class));

        // Send message to a non-existent room
        UUID fakeRoomId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("room_id", fakeRoomId.toString(), "content", "This should fail");
        Map error = sendUntilReceived(session, "/app/chat.send", payload, errors);
        assertThat(error).isNotNull();
        assertThat(error.get("status")).isNotNull();
        assertThat(error.get("message")).isNotNull();
    }

    // ---- Requirement 12.5: Unauthenticated STOMP connections are rejected ----

    /**
     * Validates Requirement 12.5 / R1-62: an unauthenticated CONNECT is rejected, so no usable
     * session is established (the interceptor throws before CONNECTED, closing the transport).
     * Split out of the former catch-all so the CONNECT rejection is asserted directly (R4-15).
     */
    @Test
    void unauthenticatedConnect_isRejected() throws Exception {
        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        CompletableFuture<Throwable> failureFuture = new CompletableFuture<>();

        WebSocketStompClient stompClient = createStompClient();
        String wsUrl = "ws://localhost:" + port + "/ws";

        stompClient.connectAsync(
                wsUrl, new WebSocketHttpHeaders(), new StompHeaders(), new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }

                    @Override
                    public void handleException(
                            @NonNull StompSession session,
                            StompCommand command,
                            @NonNull StompHeaders headers,
                            @NonNull byte[] payload,
                            @NonNull Throwable exception) {
                        failureFuture.complete(exception);
                    }

                    @Override
                    public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                        failureFuture.complete(exception);
                    }
                });

        // The rejection surfaces as an error/transport failure and no successful CONNECTED frame.
        Throwable failure = failureFuture.get(5, TimeUnit.SECONDS);
        assertThat(failure).isNotNull();
        assertThat(sessionFuture).isNotCompleted();
    }

    /**
     * Validates Requirement 12.5 / R4-15: an authenticated but non-member send to a room is denied
     * — the handler rejects it and the error is delivered to the sender's personal error queue.
     */
    @Test
    void authenticatedNonMember_sendToRoom_deliversErrorToUserQueue() throws Exception {
        // A private room only userA belongs to; userB is authenticated but not a member.
        Room privateRoom = roomService.getRoomById(roomService
                .createRoom(
                        "ws-priv-" + UUID.randomUUID().toString().substring(0, 8), "p", RoomVisibility.PRIVATE, userA)
                .id());

        String sessionCookieB = authenticateViaHttp(userB.getEmail(), "testpass123");
        StompSession sessionB = connectStomp(sessionCookieB);

        BlockingQueue<Map> errors = new LinkedBlockingQueue<>();
        sessionB.subscribe("/user/queue/errors", new QueueFrameHandler<>(errors, Map.class));

        Map<String, Object> payload = Map.of("room_id", privateRoom.getId().toString(), "content", "I am not a member");
        Map error = sendUntilReceived(sessionB, "/app/chat.send", payload, errors);

        assertThat(error).isNotNull();
        assertThat(error.get("message")).isNotNull();
    }

    /**
     * Validates R1-09 / R4-08 at the transport level: a non-member's SUBSCRIBE to a private room is
     * rejected by the channel interceptor, so a message broadcast to that room never reaches them.
     */
    @Test
    void nonMemberSubscribeToPrivateRoom_receivesNoBroadcast() throws Exception {
        Room privateRoom = roomService.getRoomById(roomService
                .createRoom(
                        "ws-priv-" + UUID.randomUUID().toString().substring(0, 8), "p", RoomVisibility.PRIVATE, userA)
                .id());

        String sessionCookieB = authenticateViaHttp(userB.getEmail(), "testpass123");
        StompSession sessionB = connectStomp(sessionCookieB);

        // The interceptor rejects this SUBSCRIBE; userB must receive nothing on the topic.
        BlockingQueue<Map> receivedByB = new LinkedBlockingQueue<>();
        sessionB.subscribe("/topic/room." + privateRoom.getId(), new QueueFrameHandler<>(receivedByB, Map.class));

        String sessionCookieA = authenticateViaHttp(userA.getEmail(), "testpass123");
        StompSession sessionA = connectStomp(sessionCookieA);
        Map<String, Object> payload = Map.of("room_id", privateRoom.getId().toString(), "content", "members only");
        sessionA.send("/app/chat.send", payload);

        // Give the broadcast ample time; the non-member's rejected subscription must stay empty.
        assertThat(receivedByB.poll(2, TimeUnit.SECONDS)).isNull();
    }

    // ---- Requirement 12.6: Message sent by User A received by User B ----

    /**
     * Validates Requirement 12.6: A message sent via WebSocket by User A in a Room
     * is received by User B who is subscribed to that Room's topic.
     */
    @Test
    void messageSentByUserA_receivedByUserB() throws Exception {
        String sessionCookieA = authenticateViaHttp(userA.getEmail(), "testpass123");
        String sessionCookieB = authenticateViaHttp(userB.getEmail(), "testpass123");

        StompSession sessionA = connectStomp(sessionCookieA);
        StompSession sessionB = connectStomp(sessionCookieB);

        // User B subscribes to the room topic
        BlockingQueue<Map> receivedByB = new LinkedBlockingQueue<>();
        sessionB.subscribe("/topic/room." + room.getId(), new QueueFrameHandler<>(receivedByB, Map.class));

        // User A sends a message; resend until B's subscription is live and receives it (R4-09).
        Map<String, Object> payload = Map.of("room_id", room.getId().toString(), "content", "Hello from User A!");
        Map message = sendUntilReceived(sessionA, "/app/chat.send", payload, receivedByB);
        assertThat(message).isNotNull();
        assertThat(message.get("content")).isEqualTo("Hello from User A!");
        assertThat(message.get("sender_username")).isEqualTo(userA.getUsername());
    }

    // ---- Helper methods ----

    /** Polls a condition until true or a 5s timeout, so tests react as soon as state changes (R1-53). */
    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    /**
     * Sends a frame and waits for a matching delivery, resending until the subscription is live
     * instead of sleeping a fixed interval before a single send (R4-09). The SimpleBroker gives no
     * SUBSCRIBE ack, so under CI load the first send can race ahead of subscription registration and
     * be lost with no replay; resending closes that race deterministically. Returns null on timeout.
     */
    private <T> T sendUntilReceived(StompSession session, String destination, Object payload, BlockingQueue<T> queue)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        T item = null;
        while (item == null && System.currentTimeMillis() < deadline) {
            session.send(destination, payload);
            item = queue.poll(250, TimeUnit.MILLISECONDS);
        }
        return item;
    }

    private WebSocketStompClient createStompClient() {
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        WebSocketStompClient stompClient = new WebSocketStompClient(webSocketClient);
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        stompClients.add(stompClient);
        return stompClient;
    }

    private StompSession connectStomp(String sessionCookie) throws Exception {
        WebSocketStompClient stompClient = createStompClient();
        String wsUrl = "ws://localhost:" + port + "/ws";

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);

        CompletableFuture<StompSession> future = new CompletableFuture<>();
        stompClient.connectAsync(wsUrl, headers, new StompHeaders(), new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                future.complete(session);
            }

            @Override
            public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                future.completeExceptionally(exception);
            }
        });

        StompSession session = future.get(10, TimeUnit.SECONDS);
        stompSessions.add(session);
        return session;
    }

    /**
     * Authenticates via HTTP form login and returns the session cookie string.
     * First GETs /login to obtain a CSRF token and session cookie, then POSTs
     * the login form with credentials and CSRF token.
     */
    private String authenticateViaHttp(String email, String password) {
        try {
            // Step 1: GET /login to obtain CSRF token and initial session cookie
            java.net.URL loginPageUrl =
                    java.net.URI.create("http://localhost:" + port + "/login").toURL();
            java.net.HttpURLConnection getConn = (java.net.HttpURLConnection) loginPageUrl.openConnection();
            getConn.setRequestMethod("GET");
            getConn.setInstanceFollowRedirects(false);

            getConn.getResponseCode(); // ensure the request completes
            String initialCookie = extractSessionCookie(getConn.getHeaderFields());
            String pageBody =
                    new String(getConn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            // Extract CSRF token from the login page HTML
            String csrfToken = extractCsrfToken(pageBody);

            // Step 2: POST /login with credentials, CSRF token, and session cookie
            java.net.HttpURLConnection postConn = (java.net.HttpURLConnection) loginPageUrl.openConnection();
            postConn.setRequestMethod("POST");
            postConn.setInstanceFollowRedirects(false);
            postConn.setDoOutput(true);
            postConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (initialCookie != null) {
                postConn.setRequestProperty("Cookie", initialCookie);
            }

            String body = "email=" + java.net.URLEncoder.encode(email, "UTF-8") + "&password="
                    + java.net.URLEncoder.encode(password, "UTF-8");
            if (csrfToken != null) {
                body += "&_csrf=" + java.net.URLEncoder.encode(csrfToken, "UTF-8");
            }
            postConn.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            int postStatus = postConn.getResponseCode();
            if (postStatus != 302 && postStatus != 200) {
                throw new IllegalStateException("Login failed with status %d for %s".formatted(postStatus, email));
            }

            // Extract session cookie from the POST response (may be a new session)
            String postCookie = extractSessionCookie(postConn.getHeaderFields());
            if (postCookie != null) {
                return postCookie;
            }
            // If no new cookie in POST response, the initial session cookie is still valid
            if (initialCookie != null) {
                return initialCookie;
            }

            throw new IllegalStateException("No session cookie returned from login for " + email);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to authenticate " + email, e);
        }
    }

    private String extractSessionCookie(Map<String, List<String>> headers) {
        List<String> setCookies = headers.getOrDefault("Set-Cookie", headers.getOrDefault("set-cookie", List.of()));
        for (String cookieHeader : setCookies) {
            List<HttpCookie> cookies = HttpCookie.parse(cookieHeader);
            for (HttpCookie cookie : cookies) {
                if ("JSESSIONID".equalsIgnoreCase(cookie.getName()) || "SESSION".equalsIgnoreCase(cookie.getName())) {
                    return cookie.getName() + "=" + cookie.getValue();
                }
            }
        }
        return null;
    }

    private String extractCsrfToken(String html) {
        // Look for <input type="hidden" name="_csrf" value="..."/>
        int idx = html.indexOf("name=\"_csrf\"");
        if (idx == -1) {
            return null;
        }
        // Search for value attribute near the _csrf input
        int valueIdx = html.indexOf("value=\"", Math.max(0, idx - 100));
        if (valueIdx == -1 || valueIdx > idx + 100) {
            valueIdx = html.indexOf("value=\"", idx);
        }
        if (valueIdx == -1) {
            return null;
        }
        int start = valueIdx + 7;
        int end = html.indexOf("\"", start);
        if (end == -1) {
            return null;
        }
        return html.substring(start, end);
    }

    /**
     * Generic STOMP frame handler that puts received payloads into a BlockingQueue.
     */
    private record QueueFrameHandler<T>(BlockingQueue<T> queue, Class<T> payloadType) implements StompFrameHandler {
        @Override
        @NonNull
        public Type getPayloadType(@NonNull StompHeaders headers) {
            return payloadType;
        }

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            queue.offer(payloadType.cast(payload));
        }
    }
}
