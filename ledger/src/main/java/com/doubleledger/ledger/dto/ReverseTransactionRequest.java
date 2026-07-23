package com.doubleledger.ledger.dto;

public class ReverseTransactionRequest {

    private String description;

    public ReverseTransactionRequest() {}

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
