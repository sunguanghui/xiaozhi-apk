package com.lhht.xiaozhi.audio;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的 PCM 样本环形缓冲区（16-bit mono, short 单位）。
 * push() 由解码线程调用写入；pull() 由独立播放线程按固定节奏调用读取。
 * 写满时丢弃最旧的样本（可通过 getDroppedSamples 观测）；
 * 读取时数据不足则用静音（0）补齐，播放节奏与网络到达节奏解耦。
 */
public class PcmFifo {
    private final short[] buffer;
    private final int capacity;
    private int readPos = 0;
    private int writePos = 0;
    private int available = 0;
    private final Object lock = new Object();
    private final AtomicLong droppedSamples = new AtomicLong();

    public PcmFifo(int capacitySamples) {
        this.capacity = capacitySamples;
        this.buffer = new short[capacitySamples];
    }

    public void push(short[] samples, int offset, int length) {
        synchronized (lock) {
            if (length > capacity) {
                offset += length - capacity;
                length = capacity;
            }
            int freeSpace = capacity - available;
            if (length > freeSpace) {
                int overflow = length - freeSpace;
                readPos = (readPos + overflow) % capacity;
                available -= overflow;
                droppedSamples.addAndGet(overflow);
            }
            for (int i = 0; i < length; i++) {
                buffer[writePos] = samples[offset + i];
                writePos = (writePos + 1) % capacity;
            }
            available += length;
        }
    }

    /** 读取 length 个样本到 dest；不足部分补 0（静音）。返回实际读取到的有效样本数。 */
    public int pull(short[] dest, int offset, int length) {
        synchronized (lock) {
            int toRead = Math.min(length, available);
            for (int i = 0; i < toRead; i++) {
                dest[offset + i] = buffer[readPos];
                readPos = (readPos + 1) % capacity;
            }
            available -= toRead;
            for (int i = toRead; i < length; i++) {
                dest[offset + i] = 0;
            }
            return toRead;
        }
    }

    public int available() {
        synchronized (lock) {
            return available;
        }
    }

    public void clear() {
        synchronized (lock) {
            readPos = 0;
            writePos = 0;
            available = 0;
        }
    }

    public long getDroppedSamples() {
        return droppedSamples.get();
    }
}
