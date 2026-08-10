package io.veridex.knowledge.infrastructure;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.veridex.knowledge.application.ObjectStorage;
import io.veridex.shared.infrastructure.config.MinioProperties;
import java.io.InputStream;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorage(MinioClient minioClient, MinioProperties properties) {
        this.client = minioClient;
        this.bucket = properties.bucket();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot ensure MinIO bucket " + bucket, e);
        }
    }

    @Override
    public void put(String objectKey, InputStream data, String contentType, long size) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(objectKey).stream(data, size, -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new RuntimeException("failed to put object " + objectKey, e);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new RuntimeException("failed to get object " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new RuntimeException("failed to delete object " + objectKey, e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String bucket() {
        return bucket;
    }
}
