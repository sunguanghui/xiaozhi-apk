package com.lhht.xiaozhi.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PcmFifoTest {

    @Test
    public void pushThenPull_returnsSameSamplesInOrder() {
        PcmFifo fifo = new PcmFifo(100);
        short[] input = {1, 2, 3, 4, 5};
        fifo.push(input, 0, input.length);

        short[] output = new short[5];
        int read = fifo.pull(output, 0, 5);

        assertEquals(5, read);
        assertEquals(1, output[0]);
        assertEquals(5, output[4]);
    }

    @Test
    public void pull_withInsufficientData_padsWithZeroAndReturnsActualCount() {
        PcmFifo fifo = new PcmFifo(100);
        short[] input = {7, 8, 9};
        fifo.push(input, 0, input.length);

        short[] output = new short[10];
        int read = fifo.pull(output, 0, 10);

        assertEquals(3, read);
        assertEquals(7, output[0]);
        assertEquals(9, output[2]);
        // 剩余部分补 0（静音），播放线程读不到数据时不应输出垂圾值
        for (int i = 3; i < 10; i++) {
            assertEquals(0, output[i]);
        }
    }

    @Test
    public void push_beyondCapacity_dropsOldestSamplesAndTracksCount() {
        PcmFifo fifo = new PcmFifo(4);
        fifo.push(new short[]{1, 2, 3, 4}, 0, 4);
        // 缓冲区已满，再写入 2 个样本应丢弃最旧的 2 个（1, 2）
        fifo.push(new short[]{5, 6}, 0, 2);

        assertEquals(4, fifo.available());
        assertEquals(2, fifo.getDroppedSamples());

        short[] output = new short[4];
        fifo.pull(output, 0, 4);
        assertEquals(3, output[0]);
        assertEquals(4, output[1]);
        assertEquals(5, output[2]);
        assertEquals(6, output[3]);
    }

    @Test
    public void clear_resetsAvailableToZero() {
        PcmFifo fifo = new PcmFifo(10);
        fifo.push(new short[]{1, 2, 3}, 0, 3);
        assertEquals(3, fifo.available());

        fifo.clear();

        assertEquals(0, fifo.available());
        short[] output = new short[3];
        int read = fifo.pull(output, 0, 3);
        assertEquals(0, read);
    }

    @Test
    public void available_reflectsPendingSamplesAfterPartialPull() {
        PcmFifo fifo = new PcmFifo(10);
        fifo.push(new short[]{1, 2, 3, 4}, 0, 4);

        short[] output = new short[2];
        fifo.pull(output, 0, 2);

        assertEquals(2, fifo.available());
    }
}
