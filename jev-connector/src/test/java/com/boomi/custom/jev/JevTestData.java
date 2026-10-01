package com.boomi.custom.jev;

/** Shared fixtures based on the published JEV request/response examples. */
public final class JevTestData {

    public static final String QUESTION_SET = "{"
            + "\"department\":{\"type\":\"choice\",\"instructions\":\"Which team should handle this request?\","
            + "\"criteria\":{\"billing\":\"Payments, invoices, refunds, or payouts\","
            + "\"technical\":\"Bugs, outages, or integration failures\",\"sales\":\"Pricing, upgrades, or new accounts\"}},"
            + "\"needs_human\":{\"type\":\"noul\",\"instructions\":\"Does this request require human review?\"}"
            + "}";

    public static final String RESPONSE = "{"
            + "\"model\":\"jev-1.13.0\","
            + "\"answers\":{"
            + "\"department\":{\"type\":\"choice\",\"choice\":\"billing\","
            + "\"probabilities\":{\"billing\":0.94,\"technical\":0.05,\"sales\":0.01},\"confidence\":0.92},"
            + "\"needs_human\":{\"type\":\"noul\",\"noul\":0.87}"
            + "},"
            + "\"usage\":{\"input_tokens\":180,\"output_tokens\":24}"
            + "}";

    private JevTestData() {
    }
}
