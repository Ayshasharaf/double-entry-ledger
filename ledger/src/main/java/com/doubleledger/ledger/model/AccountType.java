package com.doubleledger.ledger.model;

public enum AccountType {
    asset, liability, equity, revenue, expense;

    public boolean isDebitNormal() {
        return this == asset || this == expense;
    }
}