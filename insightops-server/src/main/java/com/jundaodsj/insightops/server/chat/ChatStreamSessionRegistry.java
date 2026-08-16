package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.model.application.ChatStreamSession;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ChatStreamSessionRegistry {

    private final ConcurrentMap<String, Entry> sessions = new ConcurrentHashMap<>();

    public void register(String runId, Runnable onUserCancel) {
        Entry previous = sessions.putIfAbsent(runId, new Entry(onUserCancel));
        if (previous != null) {
            throw new IllegalStateException("Duplicate chat stream runId");
        }
    }

    public void attach(String runId, ChatStreamSession session) {
        Entry entry = sessions.get(runId);
        if (entry == null) {
            session.cancel();
            return;
        }
        entry.attach(session);
    }

    public boolean cancel(String runId) {
        Entry entry = sessions.remove(runId);
        if (entry == null) {
            return false;
        }
        entry.close(true);
        return true;
    }

    public boolean disconnect(String runId) {
        Entry entry = sessions.remove(runId);
        if (entry == null) {
            return false;
        }
        entry.close(false);
        return true;
    }

    public void complete(String runId) {
        sessions.remove(runId);
    }

    public int activeCount() {
        return sessions.size();
    }

    public boolean isActive(String runId) {
        return sessions.containsKey(runId);
    }

    private static final class Entry {

        private final AtomicReference<ChatStreamSession> session = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Runnable onUserCancel;

        private Entry(Runnable onUserCancel) {
            this.onUserCancel = onUserCancel;
        }

        private void attach(ChatStreamSession streamSession) {
            if (!session.compareAndSet(null, streamSession)) {
                streamSession.cancel();
                throw new IllegalStateException("Chat stream session already attached");
            }
            if (closed.get()) {
                streamSession.cancel();
            }
        }

        private void close(boolean notifyUserCancel) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ChatStreamSession active = session.get();
            if (active != null) {
                active.cancel();
            }
            if (notifyUserCancel) {
                onUserCancel.run();
            }
        }
    }
}
