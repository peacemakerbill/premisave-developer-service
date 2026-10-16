package com.premisave.developer.wallet;

import java.util.Map;

/** A Premisave consumer wallet, as the wallet service reports it. */
public record WalletInfo(String walletId, String accountNumber, boolean frozen) {

    public static WalletInfo from(Map<String, Object> data) {
        return new WalletInfo(String.valueOf(data.get("walletId")),
                data.get("accountNumber") == null ? null : String.valueOf(data.get("accountNumber")),
                Boolean.TRUE.equals(data.get("frozen")));
    }
}
