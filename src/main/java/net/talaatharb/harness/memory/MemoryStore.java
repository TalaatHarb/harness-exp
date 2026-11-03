package net.talaatharb.harness.memory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Where memories are kept. Implementations are thread-safe. */
public interface MemoryStore extends AutoCloseable {

    /** Most recently updated first. */
    List<MemoryEntry> list();

    Optional<MemoryEntry> get(String id);

    /**
     * Inserts ({@code id == null}, or an id not in the store yet) or replaces an entry. Sets the id and timestamps
     * ({@code createdAt} is kept when already set).
     *
     * @return the stored copy
     */
    MemoryEntry save(MemoryEntry entry) throws IOException;

    boolean delete(String id) throws IOException;

    /** Folder or file, for display. */
    String location();

    @Override
    default void close() {
    }
}
