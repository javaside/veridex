package io.veridex.knowledge.api;

import java.io.InputStream;

public interface ObjectStorage {
    void put(String objectKey, InputStream data, String contentType, long size);
    InputStream get(String objectKey);
    void delete(String objectKey);
    boolean exists(String objectKey);
    String bucket();
}
