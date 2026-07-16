package com.bovae.yac.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovae.yac.config.TestcontainersConfig;
import com.bovae.yac.model.dto.MessagePage;
import com.bovae.yac.model.dto.RoomDto;
import com.bovae.yac.model.entity.Room;
import com.bovae.yac.model.entity.User;
import com.bovae.yac.model.enums.RoomVisibility;
import com.bovae.yac.repository.UserRepository;
import com.bovae.yac.service.MessageService;
import com.bovae.yac.service.RoomMemberService;
import com.bovae.yac.service.RoomService;
import com.bovae.yac.service.UserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * N+1 query detection via Hibernate statistics.
 *
 * <p>The read paths in this app are deliberately batched — message history uses {@code JOIN FETCH}
 * for sender/reply-to plus a single grouped attachment lookup, and the room sidebar collapses
 * unread counts, last-message times, and DM counterparts into grouped queries. These tests lock in
 * that behavior: they assert the number of JDBC statements Hibernate prepares stays <em>constant</em>
 * when the row count grows several-fold. If a future change drops a fetch join or reintroduces a
 * per-row lookup, the statement count scales with the data and the equality assertion fails,
 * surfacing the N+1 regression in CI rather than in production.
 *
 * <p>See {@link HibernateQueryCounter} for the measurement mechanism.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@Transactional
class NPlusOneQueryIT {

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomMemberService roomMemberService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    private HibernateQueryCounter queryCounter;

    @BeforeEach
    void setUp() {
        queryCounter = new HibernateQueryCounter(entityManagerFactory);
    }

    @Test
    void getMessageHistory_shouldNotIssueMoreQueries_whenMessageCountGrows() {
        // Several distinct senders so a dropped `JOIN FETCH m.sender` would fan out per sender.
        List<User> senders = registerUsers("mh", 4);
        Room room = createRoomWith("nplus1-message-history", senders);

        sendRoundRobin(room, senders, 3);
        long baselineQueries = queryCounter.countPreparedStatements(() -> loadHistory(room));

        sendRoundRobin(room, senders, 9); // 12 messages total — 4x the baseline
        long scaledQueries = queryCounter.countPreparedStatements(() -> loadHistory(room));

        // Constant query count regardless of how many messages/senders the page contains.
        assertThat(scaledQueries).isEqualTo(baselineQueries);
        // Fetch-join for messages (+sender +reply-to) plus one grouped attachment lookup.
        assertThat(baselineQueries).isLessThanOrEqualTo(3);
    }

    @Test
    void listUserRoomsWithUnread_shouldNotIssueMoreQueries_whenRoomCountGrows() {
        User user = registerUsers("sidebar", 1).getFirst();

        createRoomsForOwnerWithMessage(user, "nplus1-sidebar-small", 2);
        long baselineQueries = queryCounter.countPreparedStatements(() -> loadSidebar(user));

        createRoomsForOwnerWithMessage(user, "nplus1-sidebar-large", 6); // 8 rooms total — 4x
        long scaledQueries = queryCounter.countPreparedStatements(() -> loadSidebar(user));

        // Constant query count regardless of how many rooms the user belongs to.
        assertThat(scaledQueries).isEqualTo(baselineQueries);
        // Memberships fetch + grouped unread counts + grouped last-message instants.
        assertThat(baselineQueries).isLessThanOrEqualTo(4);
    }

    // ---- measured actions ----

    private void loadHistory(Room room) {
        MessagePage page = messageService.getMessageHistory(room, null, 100);
        // Touch the materialized responses so any lazy sender/reply-to access happens under measurement.
        page.messages().forEach(m -> {
            m.senderUsername();
            m.replyToId();
        });
    }

    private void loadSidebar(User user) {
        // Touch every entry so any lazy room/owner/counterpart access happens under measurement.
        roomService.listUserRoomsWithUnread(user).forEach(entry -> {
            entry.name();
            entry.otherUsername();
        });
    }

    // ---- arrangement helpers ----

    private List<User> registerUsers(String prefix, int count) {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            users.add(IntegrationTestSupport.registerUser(
                    userService,
                    userRepository,
                    "%s-user%d@test.com".formatted(prefix, i),
                    "%s_user%d".formatted(prefix, i)));
        }
        return users;
    }

    private Room createRoomWith(String name, List<User> members) {
        RoomDto dto = roomService.createRoom(name, "N+1 detection room", RoomVisibility.PUBLIC, members.getFirst());
        Room room = roomService.getRoomById(dto.id());
        members.stream().skip(1).forEach(member -> roomMemberService.joinPublicRoom(room, member));
        return room;
    }

    private void sendRoundRobin(Room room, List<User> senders, int messageCount) {
        for (int i = 0; i < messageCount; i++) {
            User sender = senders.get(i % senders.size());
            messageService.sendMessage(room, sender, "message %d".formatted(i), null);
        }
        resetPersistenceContext();
    }

    private void createRoomsForOwnerWithMessage(User owner, String namePrefix, int count) {
        for (int i = 0; i < count; i++) {
            RoomDto dto = roomService.createRoom(
                    "%s-%d".formatted(namePrefix, i), "sidebar room", RoomVisibility.PUBLIC, owner);
            Room room = roomService.getRoomById(dto.id());
            messageService.sendMessage(room, owner, "seed message", null);
        }
        resetPersistenceContext();
    }

    /**
     * Flush pending inserts to the database, then detach everything so the measured read starts from
     * a cold first-level cache. Without this the setup entities would already be managed and could
     * mask the very extra queries these tests exist to catch.
     */
    private void resetPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }
}
