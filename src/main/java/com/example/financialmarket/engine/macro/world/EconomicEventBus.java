package com.example.financialmarket.engine.macro.world;

import com.example.financialmarket.engine.macro.world.WorldMacroModel.EconomicEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded FIFO for economic events. Producers submit from the server thread; the aggregator drains
 * once per macro step. Overflow is aggregated by the caller (we keep the oldest/newest bounded and
 * count dropped events for diagnostics).
 */
public final class EconomicEventBus {

    private final Deque<EconomicEvent> queue = new ArrayDeque<>();
    private final int capacity;
    private long sequence;
    private long submitted;
    private long dropped;

    public EconomicEventBus() {
        this(20_000);
    }

    public EconomicEventBus(int capacity) {
        this.capacity = Math.max(64, capacity);
    }

    public long nextSeq() {
        return ++sequence;
    }

    /** Adds an event; emits at most {@code capacity} pending events. */
    public boolean add(EconomicEvent event) {
        if (event == null) {
            return false;
        }
        submitted++;
        if (queue.size() >= capacity) {
            dropped++;
            // Keep the newest: drop the oldest pending event.
            queue.pollFirst();
        }
        queue.addLast(event);
        return true;
    }

    public List<EconomicEvent> drain(int max) {
        int n = Math.min(Math.max(0, max), queue.size());
        List<EconomicEvent> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            EconomicEvent e = queue.pollFirst();
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    public List<EconomicEvent> drainAll() {
        return drain(queue.size());
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }

    public long submitted() {
        return submitted;
    }

    public long dropped() {
        return dropped;
    }

    public void clear() {
        queue.clear();
        submitted = 0;
        dropped = 0;
    }
}
