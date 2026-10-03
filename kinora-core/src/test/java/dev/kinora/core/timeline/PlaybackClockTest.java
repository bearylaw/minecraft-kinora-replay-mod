package dev.kinora.core.timeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackClockTest {
    private static final long MS = 1_000_000L;

    @Test
    void realTimeAdvancesTwentyTicksPerSecond() {
        PlaybackClock clock = new PlaybackClock();
        clock.advance(0);
        clock.advance(1000 * MS);
        assertEquals(20.0, clock.time(), 1e-9);
        assertEquals(20, clock.ticksToRun(0));
        assertEquals(0, clock.ticksToRun(20));
        assertEquals(0.0f, clock.partialTick());
    }

    @Test
    void speedScalesAndPartialTickIsTheFraction() {
        PlaybackClock clock = new PlaybackClock();
        clock.setSpeed(0.1);
        clock.advance(0);
        clock.advance(125 * MS);
        assertEquals(0.25, clock.time(), 1e-9);
        assertEquals(0, clock.ticksToRun(0));
        assertEquals(0.25f, clock.partialTick(), 1e-6);
    }

    @Test
    void pausedClockDoesNotMoveAndResumesWithoutAJump() {
        PlaybackClock clock = new PlaybackClock();
        clock.advance(0);
        clock.advance(500 * MS);
        clock.setPaused(true);
        clock.advance(10_000 * MS);
        assertEquals(10.0, clock.time(), 1e-9);
        clock.setPaused(false);
        clock.advance(20_000 * MS);
        clock.advance(20_050 * MS);
        assertEquals(11.0, clock.time(), 1e-9);
    }

    @Test
    void stopsAtTheEndAndLoopsWhenAsked() {
        PlaybackClock clock = new PlaybackClock();
        clock.setEnd(30);
        for (int i = 0; i <= 5; i++) {
            clock.advance(i * 1000 * MS);
        }
        assertEquals(30, clock.time(), 1e-9);
        assertTrue(clock.paused());

        PlaybackClock loop = new PlaybackClock();
        loop.setLoop(10, 20);
        loop.setTime(19);
        loop.advance(0);
        assertTrue(loop.advance(100 * MS));
        assertEquals(10, loop.time(), 1e-9);
    }

    @Test
    void ticksPerFrameAreCappedAndPartialStaysBelowOne() {
        PlaybackClock clock = new PlaybackClock();
        clock.setTime(1000.9999999999);
        assertEquals(PlaybackClock.MAX_TICKS_PER_FRAME, clock.ticksToRun(0));
        assertTrue(clock.partialTick() < 1.0f);
        assertFalse(clock.looping());
    }
}
