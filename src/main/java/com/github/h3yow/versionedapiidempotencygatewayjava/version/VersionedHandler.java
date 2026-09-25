package com.github.h3yow.versionedapiidempotencygatewayjava.version;

/** 某一主版本下的业务处理路径。 */
public interface VersionedHandler {

    int supportedMajor();

    Object handle(Object request, String requestId);
}
