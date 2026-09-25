package com.github.h3yow.versionedapiidempotencygatewayjava.idem;

/** 单个幂等键的状态记录（仅在持有键锁时访问其可变字段）。 */
public class IdempotencyRecord {

    public enum State { IN_PROGRESS, SUCCEEDED, FAILED }

    private volatile State state;
    private volatile Object result;
    private volatile String requestFingerprint;
    private volatile Throwable failure;

    public IdempotencyRecord(State state, String requestFingerprint) {
        this.state = state;
        this.requestFingerprint = requestFingerprint;
    }

    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }

    public String getRequestFingerprint() { return requestFingerprint; }
    public void setRequestFingerprint(String fp) { this.requestFingerprint = fp; }

    public Throwable getFailure() { return failure; }
    public void setFailure(Throwable failure) { this.failure = failure; }
}
