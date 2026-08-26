package com.robotemi.agent.media.v11;

import org.junit.Test;

import static org.junit.Assert.*;

public class PlaybackStateMachineTest {
    @Test
    public void supportsCanonicalLifecycle() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.start("s");
        machine.started("s");
        machine.pause("s");
        machine.resume("s");
        machine.beginStop("s");
        assertTrue(machine.cancel("s"));
        assertEquals(PlaybackStateMachine.State.CANCELLED, machine.getState());
        assertFalse(machine.cancel("s"));
        machine.clearTerminal();
        assertEquals(PlaybackStateMachine.State.IDLE, machine.getState());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsWrongTargetBeforeTransition() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.start("s");
        machine.started("s");
        machine.pause("other");
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsPauseUnlessPlaying() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.start("s");
        machine.pause("s");
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsResumeUnlessPaused() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.start("s");
        machine.started("s");
        machine.resume("s");
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsStopWhileStarting() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.start("s");
        machine.beginStop("s");
    }
}
