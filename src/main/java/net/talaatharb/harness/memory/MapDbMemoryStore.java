package net.talaatharb.harness.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;
import org.mapdb.Serializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Memories as JSON strings in a transactional MapDB file. Opened on first use; if the file can't be opened (e.g.
 * another instance holds the lock) operations fail explicitly rather than claiming volatile data was saved.
 */
public class MapDbMemoryStore implements MemoryStore {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Path path;
    private DB db;
    private HTreeMap<String, String> memories;

    public MapDbMemoryStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    @Override
    public String location() {
        return path.toString();
    }

    private HTreeMap<String, String> map() {
        if (db == null || db.isClosed()) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                db = DBMaker.fileDB(path.toFile()).transactionEnable().closeOnJvmShutdown().make();
                memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
                // Commit initialization so the WAL does not keep the file locked after close on Windows.
                db.commit();
            } catch (IOException | RuntimeException e) {
                if (db != null && !db.isClosed()) {
                    try {
                        db.close();
                    } catch (RuntimeException closeError) {
                        e.addSuppressed(closeError);
                    }
                }
                db = null;
                memories = null;
                throw new IllegalStateException("Could not open persistent memory DB at " + path, e);
            }
        }
        return memories;
    }

    @Override
    public synchronized List<MemoryEntry> list() {
        List<MemoryEntry> result = new ArrayList<>();
        for (var entry : map().entrySet()) {
            try {
                MemoryEntry e = mapper.readValue(entry.getValue(), MemoryEntry.class);
                e.id = entry.getKey();
                result.add(e);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Could not read memory " + entry.getKey(), e);
            }
        }
        result.sort(MemoryService.NEWEST_FIRST);
        return result;
    }

    @Override
    public synchronized Optional<MemoryEntry> get(String id) {
        String json = id == null ? null : map().get(id);
        if (json == null) {
            return Optional.empty();
        }
        try {
            MemoryEntry e = mapper.readValue(json, MemoryEntry.class);
            e.id = id;
            return Optional.of(e);
        } catch (IOException e) {
            throw new IllegalStateException("Memory " + id + " is unreadable", e);
        }
    }

    @Override
    public synchronized MemoryEntry save(MemoryEntry entry) throws IOException {
        MemoryEntry e = entry.copy();
        Instant now = Instant.now();
        HTreeMap<String, String> map = map();
        if (e.id == null || e.id.isBlank()) {
            e.id = MarkdownMemoryStore.newId(e.text, map::containsKey);
        }
        MarkdownMemoryStore.checkId(e.id);
        if (e.createdAt == null) {
            e.createdAt = get(e.id).map(old -> old.createdAt).orElse(now);
        }
        e.updatedAt = now;
        try {
            map.put(e.id, mapper.writeValueAsString(e));
            db.commit();
        } catch (IOException | RuntimeException ex) {
            db.rollback();
            throw new IOException("Could not save memory " + e.id + ": " + ex.getMessage(), ex);
        }
        return e;
    }

    @Override
    public synchronized boolean delete(String id) {
        boolean removed = map().remove(id) != null;
        db.commit();
        return removed;
    }

    @Override
    public synchronized void close() {
        if (db != null && !db.isClosed()) {
            db.close();
        }
    }
}
