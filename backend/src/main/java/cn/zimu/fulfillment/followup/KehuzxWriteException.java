package cn.zimu.fulfillment.followup;

/** Stable failure codes for failures that happen outside a persisted Kehuzx write result. */
public class KehuzxWriteException extends RuntimeException {

    public enum Code {
        KEHUZX_WRITE_NOT_CONFIGURED,
        KEHUZX_WRITE_TOOL_REJECTED,
        KEHUZX_WRITE_AUTH_REJECTED,
        KEHUZX_WRITE_CONTRACT_DRIFT,
        KEHUZX_WRITE_RETRYABLE,
        KEHUZX_WRITE_TIMEOUT,
        KEHUZX_WRITE_UNREACHABLE
    }

    private final Code code;

    public KehuzxWriteException(Code code) {
        super(code.name());
        this.code = code;
    }

    public KehuzxWriteException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() { return code; }
}
