package dev.stevecreate.agent.acceptance.client;

final class BridgeRefusal extends RuntimeException {
    private final String code;

    BridgeRefusal(String code) {
        this(code, "");
    }

    BridgeRefusal(String code, String detail) {
        super(detail == null ? "" : detail);
        this.code = code;
    }

    String code() {
        return code;
    }
}
