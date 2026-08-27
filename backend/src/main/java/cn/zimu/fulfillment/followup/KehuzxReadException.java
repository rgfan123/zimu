package cn.zimu.fulfillment.followup;

/** Stable, credential-free failure from the remote read-only MCP boundary. */
public class KehuzxReadException extends RuntimeException {

    public enum Code {
        KEHUZX_NOT_CONFIGURED,
        KEHUZX_UNREACHABLE,
        KEHUZX_TIMEOUT,
        KEHUZX_AUTH_REJECTED,
        KEHUZX_CONTRACT_DRIFT,
        KEHUZX_TOOL_FAILED
    }

    private final Code code;

    public KehuzxReadException(Code code) {
        super(code.name());
        this.code = code;
    }

    public KehuzxReadException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
