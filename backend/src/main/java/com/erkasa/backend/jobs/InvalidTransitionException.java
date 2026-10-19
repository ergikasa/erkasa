package com.erkasa.backend.jobs;

/**
 * Someone tried to move a project, job or step to a state it cannot go to from where it is,
 * e.g. approving a script that is still being written. Becomes HTTP 409 Conflict (4.2).
 */
public class InvalidTransitionException extends IllegalStateException {

    private final String from;
    private final String to;

    public InvalidTransitionException(String what, Enum<?> from, Enum<?> to) {
        super("Cannot move " + what + " from " + from + " to " + to);
        this.from = from.name();
        this.to = to.name();
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}