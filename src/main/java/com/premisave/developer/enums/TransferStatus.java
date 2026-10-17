package com.premisave.developer.enums;

/** Where a transfer or claim stands. A pending one that ran out of time is shown as EXPIRED without changing the row. */
public enum TransferStatus {
    PENDING, COMPLETED, CANCELLED,
    /** The request was refused before any code was sent. Kept only so repeated tries can be counted. */
    REJECTED
}
