package com.hunt.otziv.exceptions;

public class NagulTooFastException extends RuntimeException {
    private final long minutesLeft;
    private final long secondsLeft;

    public NagulTooFastException(long minutesLeft, long secondsLeft) {
        super(String.format("Слишком быстрый выгул! Подождите еще %d мин %d сек",
                minutesLeft, secondsLeft));
        this.minutesLeft = minutesLeft;
        this.secondsLeft = secondsLeft;
    }

    public long getMinutesLeft() {
        return minutesLeft;
    }

    public long getSecondsLeft() {
        return secondsLeft;
    }
}


