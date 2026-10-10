package com.minecraftcitiesnetwork.directions.walk.coarse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class CoarseGraphCodecTest {
    private static ComponentId id(int cx, int cz, int x, int y, int z) {
        return new ComponentId(cx, cz, x, y, z);
    }

    private static byte[] write(UUID world, CoarseGraph graph, BuildProgress progress) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CoarseGraphCodec.write(world, graph, progress, out);
        return out.toByteArray();
    }

    private static CoarseGraph sampleGraph() {
        ComponentId a = id(0, 0, 5, 64, 7);
        ComponentId b = id(1, 0, 17, -3, 8);
        ComponentId c = id(-2, 4, -30, 70, 70);
        Map<Long, List<CoarseNode>> map = new HashMap<>();
        map.put(ChunkKeys.key(0, 0), List.of(new CoarseNode(a, 60, 66, List.of(b)),
                new CoarseNode(id(0, 0, 1, 10, 1), -5, 12, List.of())));
        map.put(ChunkKeys.key(1, 0), List.of(new CoarseNode(b, -4, -2, List.of(a, c))));
        map.put(ChunkKeys.key(-2, 4), List.of(new CoarseNode(c, 70, 70, List.of(b))));
        map.put(ChunkKeys.key(9, 9), List.of());
        return CoarseGraph.of(map);
    }

    @Test
    void roundTripsNodesEdgesAndProgress() throws IOException {
        UUID world = UUID.randomUUID();
        CoarseGraph graph = sampleGraph();
        BuildProgress progress = new BuildProgress(2, -3, 17, false);

        CoarseGraphCodec.Loaded loaded = CoarseGraphCodec.read(new ByteArrayInputStream(write(world, graph, progress)));

        assertEquals(world, loaded.world());
        assertEquals(progress, loaded.progress());
        assertEquals(graph.snapshot(), loaded.graph().snapshot());
        assertTrue(loaded.graph().contains(9, 9));
        assertEquals(4, loaded.graph().size());
    }

    @Test
    void emptyGraphRoundTrips() throws IOException {
        UUID world = UUID.randomUUID();
        byte[] bytes = write(world, CoarseGraph.of(Map.of()), BuildProgress.NONE);

        CoarseGraphCodec.Loaded loaded = CoarseGraphCodec.read(new ByteArrayInputStream(bytes));

        assertEquals(world, loaded.world());
        assertEquals(BuildProgress.NONE, loaded.progress());
        assertEquals(0, loaded.graph().chunkCount());
    }

    @Test
    void truncatedStreamFailsCleanly() throws IOException {
        byte[] bytes = write(UUID.randomUUID(), sampleGraph(), new BuildProgress(0, 0, 1, true));
        byte[] half = Arrays.copyOf(bytes, bytes.length / 2);

        assertThrows(IOException.class, () -> CoarseGraphCodec.read(new ByteArrayInputStream(half)));
    }

    @Test
    void wrongMagicIsRejected() throws IOException {
        byte[] bytes = header("DCGX", 1);

        IOException e = assertThrows(IOException.class, () -> CoarseGraphCodec.read(new ByteArrayInputStream(bytes)));
        assertTrue(e.getMessage().contains("magic"));
    }

    @Test
    void newerVersionIsRejected() throws IOException {
        byte[] bytes = header("DCG1", 2);

        IOException e = assertThrows(IOException.class, () -> CoarseGraphCodec.read(new ByteArrayInputStream(bytes)));
        assertTrue(e.getMessage().contains("version"));
    }

    private static byte[] header(String magic, int version) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            out.writeBytes(magic);
            out.writeInt(version);
            out.writeLong(1);
            out.writeLong(2);
        }
        return raw.toByteArray();
    }
}
