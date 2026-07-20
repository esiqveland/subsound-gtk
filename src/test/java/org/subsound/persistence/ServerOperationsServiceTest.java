package org.subsound.persistence;

import org.subsound.app.state.NetworkMonitoring.NetworkState;
import org.subsound.app.state.NetworkMonitoring.NetworkStatus;
import org.subsound.integration.ServerClient;
import org.subsound.persistence.database.Database;
import org.subsound.persistence.database.ServerOperationStatus;
import org.subsound.persistence.database.ServerOperationType;
import org.subsound.persistence.database.ServerOperationsDao;
import org.subsound.persistence.database.ServerOperationPayload.StarSong;
import org.subsound.persistence.database.ServerOperationPayload.UnstarSong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.InOrder;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class ServerOperationsServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ServerOperationsDao newDao() throws Exception {
        File dbFile = folder.newFile("test_ops_service_" + UUID.randomUUID() + ".db");
        return new ServerOperationsDao(new Database("jdbc:sqlite:" + dbFile.getAbsolutePath()));
    }

    private ServerOperationsService service(ServerOperationsDao dao, UUID serverId, ServerClient client, NetworkStatus status) {
        return new ServerOperationsService(
                dao,
                serverId,
                () -> client,
                () -> new NetworkState(status),
                false // do not auto-start the background thread; we drive processPending() directly
        );
    }

    @Test
    public void replaysPendingOpsInOrderAndMarksCompleted() throws Exception {
        ServerOperationsDao dao = newDao();
        UUID serverId = UUID.randomUUID();
        long starId = dao.enqueue(serverId, ServerOperationType.STAR, new StarSong("song-1"), Instant.now());
        long unstarId = dao.enqueue(serverId, ServerOperationType.UNSTAR, new UnstarSong("song-2"), Instant.now());

        ServerClient client = mock(ServerClient.class);
        service(dao, serverId, client, NetworkStatus.ONLINE).processPending();

        InOrder inOrder = inOrder(client);
        inOrder.verify(client).starId("song-1");
        inOrder.verify(client).unStarId("song-2");

        assertThat(dao.listPending(serverId)).isEmpty();
        assertThat(dao.findById(starId).orElseThrow().status()).isEqualTo(ServerOperationStatus.COMPLETED);
        assertThat(dao.findById(unstarId).orElseThrow().status()).isEqualTo(ServerOperationStatus.COMPLETED);
        assertThat(dao.findById(starId).orElseThrow().completedAt()).isPresent();
    }

    @Test
    public void skipsReplayWhenOffline() throws Exception {
        ServerOperationsDao dao = newDao();
        UUID serverId = UUID.randomUUID();
        dao.enqueue(serverId, ServerOperationType.STAR, new StarSong("song-1"), Instant.now());

        ServerClient client = mock(ServerClient.class);
        service(dao, serverId, client, NetworkStatus.OFFLINE).processPending();

        verify(client, org.mockito.Mockito.never()).starId(org.mockito.Mockito.anyString());
        assertThat(dao.listPending(serverId)).hasSize(1);
    }

    @Test
    public void networkErrorKeepsOpPendingAndStopsBatch() throws Exception {
        ServerOperationsDao dao = newDao();
        UUID serverId = UUID.randomUUID();
        long id1 = dao.enqueue(serverId, ServerOperationType.STAR, new StarSong("song-1"), Instant.now());
        long id2 = dao.enqueue(serverId, ServerOperationType.STAR, new StarSong("song-2"), Instant.now());

        ServerClient client = mock(ServerClient.class);
        doThrow(new RuntimeException("boom", new IOException("connection reset")))
                .when(client).starId("song-1");

        service(dao, serverId, client, NetworkStatus.ONLINE).processPending();

        // First op stays PENDING with an attempt recorded; the batch stops so the second is untouched.
        var op1 = dao.findById(id1).orElseThrow();
        assertThat(op1.status()).isEqualTo(ServerOperationStatus.PENDING);
        assertThat(op1.executedAt()).isPresent();
        var op2 = dao.findById(id2).orElseThrow();
        assertThat(op2.status()).isEqualTo(ServerOperationStatus.PENDING);
        assertThat(op2.executedAt()).isEmpty();
        verify(client, org.mockito.Mockito.never()).starId("song-2");
    }

    @Test
    public void nonNetworkErrorMarksFailedAndContinues() throws Exception {
        ServerOperationsDao dao = newDao();
        UUID serverId = UUID.randomUUID();
        long id1 = dao.enqueue(serverId, ServerOperationType.STAR, new StarSong("song-1"), Instant.now());
        long id2 = dao.enqueue(serverId, ServerOperationType.STAR, new StarSong("song-2"), Instant.now());

        ServerClient client = mock(ServerClient.class);
        doThrow(new IllegalStateException("404 not found")).when(client).starId("song-1");

        service(dao, serverId, client, NetworkStatus.ONLINE).processPending();

        var op1 = dao.findById(id1).orElseThrow();
        assertThat(op1.status()).isEqualTo(ServerOperationStatus.FAILED);
        assertThat(op1.executedAt()).isPresent();
        assertThat(op1.completedAt()).isEmpty();
        // Batch continues past the failed op.
        assertThat(dao.findById(id2).orElseThrow().status()).isEqualTo(ServerOperationStatus.COMPLETED);
        verify(client).starId("song-2");
    }
}
