package com.minecraftcitiesnetwork.directions.walk.coarse;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Reads and writes a world's graph file: gzip over big-endian {@code DataOutput}; layout in the design spec, section 7. */
public final class CoarseGraphCodec {
    private static final byte[] MAGIC = {'D', 'C', 'G', '1'};
    /** Version 2: edge counts are unsigned shorts (version 1 had a byte, and nodes with repeated ids). */
    private static final int VERSION = 2;
    private static final int MAX_EDGES = 0xFFFF;

    public record Loaded(UUID world, CoarseGraph graph, BuildProgress progress) {
    }

    private CoarseGraphCodec() {
    }

    public static void write(UUID world, CoarseGraph graph, BuildProgress progress, OutputStream out)
            throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(gzip));
        data.write(MAGIC);
        data.writeInt(VERSION);
        data.writeLong(world.getMostSignificantBits());
        data.writeLong(world.getLeastSignificantBits());
        data.writeInt(progress.regionX());
        data.writeInt(progress.regionZ());
        data.writeInt(progress.row());
        data.writeBoolean(progress.done());

        Map<Long, List<CoarseNode>> snapshot = graph.snapshot();
        int nodeCount = 0;
        List<Long> emptyChunks = new ArrayList<>();
        for (Map.Entry<Long, List<CoarseNode>> entry : snapshot.entrySet()) {
            nodeCount += entry.getValue().size();
            if (entry.getValue().isEmpty()) {
                emptyChunks.add(entry.getKey());
            }
        }
        data.writeInt(nodeCount);
        for (Map.Entry<Long, List<CoarseNode>> entry : snapshot.entrySet()) {
            for (CoarseNode node : entry.getValue()) {
                writeNode(data, node);
            }
        }
        // Known chunks without components, which the graph tells apart from unknown chunks.
        data.writeInt(emptyChunks.size());
        for (long key : emptyChunks) {
            data.writeInt(ChunkKeys.x(key));
            data.writeInt(ChunkKeys.z(key));
        }
        // Finish the gzip trailer but leave the caller's stream open.
        data.flush();
        gzip.finish();
    }

    private static void writeNode(DataOutputStream data, CoarseNode node) throws IOException {
        ComponentId id = node.id();
        data.writeInt(id.chunkX());
        data.writeInt(id.chunkZ());
        data.writeInt(id.x());
        data.writeInt(id.y());
        data.writeInt(id.z());
        data.writeShort(node.minY());
        data.writeShort(node.maxY());
        if (node.edges().size() > MAX_EDGES) {
            throw new IllegalStateException("Node " + id + " has " + node.edges().size() + " edges, more than "
                    + MAX_EDGES);
        }
        data.writeShort(node.edges().size());
        for (ComponentId edge : node.edges()) {
            data.writeInt(edge.chunkX());
            data.writeInt(edge.chunkZ());
            data.writeInt(edge.x());
            data.writeInt(edge.y());
            data.writeInt(edge.z());
        }
    }

    public static Loaded read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(new BufferedInputStream(new GZIPInputStream(in)));
        byte[] magic = new byte[MAGIC.length];
        data.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Not a coarse graph file: bad magic");
        }
        int version = data.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported coarse graph file version " + version + " (expected " + VERSION
                    + "); rebuild with /directions walk build");
        }
        UUID world = new UUID(data.readLong(), data.readLong());
        BuildProgress progress = new BuildProgress(data.readInt(), data.readInt(), data.readInt(), data.readBoolean());

        Map<Long, List<CoarseNode>> chunks = new HashMap<>();
        // One instance per id: a node's own id and every edge to it share it, as in CoarseGraph.replace.
        Map<ComponentId, ComponentId> ids = new HashMap<>();
        int nodeCount = data.readInt();
        for (int i = 0; i < nodeCount; i++) {
            CoarseNode node = readNode(data, ids);
            chunks.computeIfAbsent(ChunkKeys.key(node.id().chunkX(), node.id().chunkZ()), k -> new ArrayList<>())
                    .add(node);
        }
        int emptyCount = data.readInt();
        for (int i = 0; i < emptyCount; i++) {
            chunks.computeIfAbsent(ChunkKeys.key(data.readInt(), data.readInt()), k -> new ArrayList<>());
        }
        return new Loaded(world, CoarseGraph.of(chunks), progress);
    }

    private static CoarseNode readNode(DataInputStream data, Map<ComponentId, ComponentId> ids) throws IOException {
        ComponentId id = readId(data, ids);
        int minY = data.readShort();
        int maxY = data.readShort();
        int edgeCount = data.readUnsignedShort();
        List<ComponentId> edges = new ArrayList<>(edgeCount);
        for (int i = 0; i < edgeCount; i++) {
            edges.add(readId(data, ids));
        }
        return new CoarseNode(id, minY, maxY, edges);
    }

    private static ComponentId readId(DataInputStream data, Map<ComponentId, ComponentId> ids) throws IOException {
        ComponentId id = new ComponentId(data.readInt(), data.readInt(), data.readInt(), data.readInt(), data.readInt());
        ComponentId known = ids.putIfAbsent(id, id);
        return known != null ? known : id;
    }
}
