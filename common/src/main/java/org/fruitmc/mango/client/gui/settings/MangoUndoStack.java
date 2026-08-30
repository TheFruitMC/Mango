package org.fruitmc.mango.client.gui.settings;

import java.util.ArrayDeque;
import java.util.Deque;

public final class MangoUndoStack {

    private final Deque<Runnable> stack = new ArrayDeque<>();
    private boolean recording = true;

    public void push(Runnable undo) {
        if (this.recording) {
            this.stack.push(undo);
        }
    }

    public boolean canUndo() {
        return !this.stack.isEmpty();
    }

    public void undo() {
        if (this.stack.isEmpty()) {
            return;
        }
        boolean wasRecording = this.recording;
        this.recording = false;
        try {
            this.stack.pop().run();
        } finally {
            this.recording = wasRecording;
        }
    }

    public void undoAll() {
        boolean wasRecording = this.recording;
        this.recording = false;
        try {
            while (!this.stack.isEmpty()) {
                this.stack.pop().run();
            }
        } finally {
            this.recording = wasRecording;
        }
    }

    public void clear() {
        this.stack.clear();
    }
}
