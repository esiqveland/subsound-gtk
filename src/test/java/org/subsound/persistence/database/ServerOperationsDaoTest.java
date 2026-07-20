package org.subsound.persistence.database;

import org.subsound.persistence.database.ServerOperationPayload.StarSong;
import org.subsound.persistence.database.ServerOperationPayload.UnstarSong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class ServerOperationsDaoTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Database newDb() throws Exception {
        File dbFile = folder.newFile("test_server_ops_" + UUID.randomUUID() + ".db");
        return new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }

    @Test
    public void enqueuePreservesOrderAndRoundTripsPayload() throws Exception {
        ServerOperationsDao dao = new ServerOperationsDao(newDb());
        UUID serverId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        long id1 = dao.enqueue(serverId, ServerOperationType.STAR, new StarSong("song-1"), now);
        long id2 = dao.enqueue(serverId, ServerOperationType.UNSTAR, new UnstarSong("song-2"), now);

        assertThat(id2).isGreaterThan(id1);

        List<ServerOperation> pending = dao.listPending(serverId);
        assertThat(pending).hasSize(2);

        ServerOperation first = pending.get(0);
        assertThat(first.id()).isEqualTo(id1);
        assertThat(first.type()).isEqualTo(ServerOperationType.STAR);
        assertThat(first.status()).isEqualTo(ServerOperationStatus.PENDING);
        assertThat(first.payload()).isEqualTo(new StarSong("song-1"));
        assertThat(first.createdAt()).isEqualTo(now);
        assertThat(first.executedAt()).isEmpty();
        assertThat(first.completedAt()).isEmpty();

        assertThat(pending.get(1).payload()).isEqualTo(new UnstarSong("song-2"));
    }

    @Test
    public void listPendingIsScopedByServer() throws Exception {
        ServerOperationsDao dao = new ServerOperationsDao(newDb());
        UUID serverA = UUID.randomUUID();
        UUID serverB = UUID.randomUUID();
        dao.enqueue(serverA, ServerOperationType.STAR, new StarSong("a"), Instant.now());
        dao.enqueue(serverB, ServerOperationType.STAR, new StarSong("b"), Instant.now());

        assertThat(dao.listPending(serverA)).hasSize(1);
        assertThat(dao.listPending(serverB)).hasSize(1);
    }

    @Test
    public void markCompletedSetsExecutedAndCompletedAndClearsPending() throws Exception {
        ServerOperationsDao dao = new ServerOperationsDao(newDb());
        UUID serverId = UUID.randomUUID();
        long id = dao.enqueue(serverId, ServerOperationType.STAR, new StarSong("song-1"), Instant.now());

        Instant executed = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant completed = executed.plusSeconds(1);
        dao.markCompleted(id, executed, completed);

        assertThat(dao.listPending(serverId)).isEmpty();

        ServerOperation op = dao.findById(id).orElseThrow();
        assertThat(op.status()).isEqualTo(ServerOperationStatus.COMPLETED);
        assertThat(op.executedAt()).contains(executed);
        assertThat(op.completedAt()).contains(completed);
    }

    @Test
    public void markFailedSetsExecutedButLeavesCompletedNullAndClearsPending() throws Exception {
        ServerOperationsDao dao = new ServerOperationsDao(newDb());
        UUID serverId = UUID.randomUUID();
        long id = dao.enqueue(serverId, ServerOperationType.UNSTAR, new UnstarSong("song-1"), Instant.now());

        Instant executed = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        dao.markFailed(id, executed);

        assertThat(dao.listPending(serverId)).isEmpty();

        ServerOperation op = dao.findById(id).orElseThrow();
        assertThat(op.status()).isEqualTo(ServerOperationStatus.FAILED);
        assertThat(op.executedAt()).contains(executed);
        assertThat(op.completedAt()).isEmpty();
    }

    @Test
    public void touchExecutedRecordsAttemptButKeepsPending() throws Exception {
        ServerOperationsDao dao = new ServerOperationsDao(newDb());
        UUID serverId = UUID.randomUUID();
        long id = dao.enqueue(serverId, ServerOperationType.STAR, new StarSong("song-1"), Instant.now());

        Instant executed = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        dao.touchExecuted(id, executed);

        List<ServerOperation> pending = dao.listPending(serverId);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).status()).isEqualTo(ServerOperationStatus.PENDING);
        assertThat(pending.get(0).executedAt()).contains(executed);
        assertThat(pending.get(0).completedAt()).isEmpty();
    }

}
