package com.lhht.xiaozhi.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LatencyTrackerTest {

    @Test
    public void summary_containsMarksInInsertionOrder() {
        LatencyTracker tracker = new LatencyTracker();
        tracker.reset();
        tracker.mark("tts_start");
        tracker.mark("first_binary");
        tracker.mark("first_decode");

        String summary = tracker.summary();

        int startIdx = summary.indexOf("tts_start");
        int binaryIdx = summary.indexOf("first_binary");
        int decodeIdx = summary.indexOf("first_decode");
        assertTrue(startIdx >= 0 && startIdx < binaryIdx);
        assertTrue(binaryIdx < decodeIdx);
    }

    @Test
    public void mark_calledTwiceForSamePoint_keepsFirstTimestamp() throws InterruptedException {
        LatencyTracker tracker = new LatencyTracker();
        tracker.reset();
        tracker.mark("first_binary");
        String firstSummary = tracker.summary();

        Thread.sleep(20);
        tracker.mark("first_binary"); // 同一 point 第二次调用应被忽略
        String secondSummary = tracker.summary();

        assertEquals(firstSummary, secondSummary);
    }

    @Test
    public void reset_clearsPreviousMarks() {
        LatencyTracker tracker = new LatencyTracker();
        tracker.reset();
        tracker.mark("tts_start");
        assertTrue(tracker.summary().contains("tts_start"));

        tracker.reset();

        assertEquals("", tracker.summary());
    }
}
