package com.github.h3yow.versionedapiidempotencygatewayjava.model;

import java.util.Objects;

/** 语义化的 API 版本号（major.minor），如 "1.0"、"2.1"。 */
public record ApiVersion(int major, int minor) implements Comparable<ApiVersion> {

    /**
     * 解析 "major.minor" 形式的版本字符串。
     *
     * @throws IllegalArgumentException 格式非法
     */
    public static ApiVersion parse(String raw) {
        Objects.requireNonNull(raw, "version");
        String trimmed = raw.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("invalid version: " + raw);
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (major < 0 || minor < 0) {
                throw new IllegalArgumentException("invalid version: " + raw);
            }
            return new ApiVersion(major, minor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid version: " + raw, e);
        }
    }

    @Override
    public int compareTo(ApiVersion other) {
        int byMajor = Integer.compare(this.major, other.major);
        return byMajor != 0 ? byMajor : Integer.compare(this.minor, other.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
