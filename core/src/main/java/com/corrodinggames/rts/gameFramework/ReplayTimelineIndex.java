package com.corrodinggames.rts.gameFramework;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** A streaming index of the existing replay format. Never deserializes commands or game states. */
public final class ReplayTimelineIndex {
    public record Checkpoint(long blockOffset, long resumeOffset, int issuedTick, int tick, int timeMillis,
                             int commandsBefore, float stepRate, float syncRate) {}

    public final int durationMillis;
    public final int endTick;
    public final long initialSaveOffset;
    public final List<Checkpoint> checkpoints;

    private ReplayTimelineIndex(int durationMillis, int endTick, long initialSaveOffset,
                                List<Checkpoint> checkpoints) {
        this.durationMillis = durationMillis;
        this.endTick = endTick;
        this.initialSaveOffset = initialSaveOffset;
        this.checkpoints = List.copyOf(checkpoints);
    }

    public Checkpoint checkpointBefore(int timeMillis) {
        Checkpoint best = null;
        for (Checkpoint checkpoint : checkpoints) {
            if (checkpoint.timeMillis <= timeMillis && (best == null || checkpoint.timeMillis >= best.timeMillis)) {
                best = checkpoint;
            }
        }
        return best;
    }

    /** Ownership of input stays with the caller. Length-prefixed payloads are skipped in constant memory. */
    public static ReplayTimelineIndex scan(InputStream input, BooleanSupplier cancelled) throws IOException {
        CountingInput counted = new CountingInput(new BufferedInputStream(input));
        DataInputStream data = new DataInputStream(counted);
        if (!"rustedWarfareReplay".equals(data.readUTF())) throw new IOException("Invalid replay header");
        data.readInt();
        int version = data.readInt();
        if (version < 11) throw new IOException("Replay block format is unsupported");
        data.readUTF();
        data.readBoolean();
        int commands = 0;
        long initial = -1;
        List<Checkpoint> checkpoints = new ArrayList<>();
        while (true) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Replay index cancelled");
            long offset = counted.position;
            String name;
            try { name = data.readUTF(); }
            catch (EOFException end) { throw new IOException("Replay has no end metadata", end); }
            int length = data.readInt();
            if (length < 0) throw new IOException("Negative replay block length");
            long end = counted.position + length;
            if ("gamesave".equals(name) && initial < 0) initial = offset;
            if ("rc".equals(name)) commands++;
            if ("resync".equals(name)) {
                if (length < 24) throw new IOException("Truncated resync block");
                int issuedTick = data.readInt(); // tick at which the resync was issued
                int tick = data.readInt();
                int time = data.readInt();
                float stepRate = data.readFloat();
                float syncRate = data.readFloat();
                int saveLength = data.readInt();
                if (tick < 0 || time < 0 || !Float.isFinite(stepRate) || stepRate < 0.1f
                        || saveLength < 0 || saveLength > end - counted.position) {
                    throw new IOException("Invalid resync block");
                }
                checkpoints.add(new Checkpoint(offset, end, issuedTick, tick, time, commands, stepRate, syncRate));
            }
            if ("endReplayMetaData".equals(name)) {
                if (length < 17 || data.readUnsignedByte() != 0) throw new IOException("Invalid end metadata");
                int tick = data.readInt();
                int duration = data.readInt();
                data.readInt();
                data.readInt();
                if (duration < 0 || tick < 0 || initial < 0) throw new IOException("Invalid replay duration");
                data.skipNBytes(end - counted.position);
                return new ReplayTimelineIndex(duration, tick, initial, checkpoints);
            }
            data.skipNBytes(end - counted.position);
        }
    }

    private static final class CountingInput extends FilterInputStream {
        long position;
        CountingInput(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) position++;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count > 0) position += count;
            return count;
        }
        @Override public long skip(long length) throws IOException {
            long count = in.skip(length);
            position += count;
            return count;
        }
    }
}
