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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Memories as JSON strings in a transactional MapDB file. Opened on first use; if the file can't be opened (e.g.
 * another instance holds the lock) it falls back to an in-memory DB so the app keeps working.
 */
public class MapDbMemoryStore implements MemoryStore {
    private static final Logger LOG = LoggerFactory.getLogger(MapDbMemoryStore.class);

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Path path;
    private DB db;
    private HTreeMap<String, String> memories;
    private boolean persistent;

    public MapDbMemoryStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    @Override
    public String location() {
        return path + (db != null && !persistent ? " (could not open: in-memory only)" : "");
    }

    private HTreeMap<String, String> map() {
        if (db == null || db.isClosed()) {
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                db = DBMaker.fileDB(path.toFile()).transactionEnable().closeOnJvmShutdown().make();
                persistent = true;
            } catch (IOException | RuntimeException e) {
                LOG.error("Could not open memory DB at {}; memories will not be kept after exit", path, e);
                db = DBMaker.memoryDB().make();
                persistent = false;
            }
            memories = db.hashMap("memories", Serializer.STRING, Serializer.STRING).createOrOpen();
            // without a commit the write-ahead log stays open after close() and keeps the file locked on Windows
            db.commit();
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
                LOG.warn("Skipping unreadable memory {}", entry.getKey(), e);
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
            LOG.warn("Memory {} is unreadable", id, e);
            return Optional.empty();
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
