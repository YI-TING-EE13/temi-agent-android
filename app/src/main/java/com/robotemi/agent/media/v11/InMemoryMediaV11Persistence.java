package com.robotemi.agent.media.v11;

/** JVM-testable persistence implementation. Android production uses SharedPreferences. */
public final class InMemoryMediaV11Persistence implements MediaV11Persistence {
    private Snapshot snapshot = new Snapshot();
    private boolean failReads;
    private boolean failWrites;

    @Override
    public synchronized Snapshot load() throws StoreException {
        if (failReads) {
            throw new StoreException("injected_read_failure");
        }
        return snapshot.copy();
    }

    @Override
    public synchronized void save(Snapshot value) throws StoreException {
        if (failWrites) {
            throw new StoreException("injected_write_failure");
        }
        snapshot = value.copy();
    }

    public synchronized void setFailReads(boolean value) {
        failReads = value;
    }

    public synchronized void setFailWrites(boolean value) {
        failWrites = value;
    }
}
