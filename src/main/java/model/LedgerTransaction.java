package model;

public interface LedgerTransaction {
    String transactionId();

    String eventId();

    String transactionType();
}
