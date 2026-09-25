package com.github.h3yow.versionedapiidempotencygatewayjava.common;

/**
 * 语义化 API 版本号（major.minor）。
 */
public record ApiVersion(int major, int minor) implements Comparable<ApiVersion> {

    private static final java.util.regex.Pattern PATTERN =
            java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)");

    /**
     * 解析形如 "1.0" 的版本声明；非法格式抛出 IllegalArgumentException。
     */
    public static ApiVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("version is blank");
        }
        var matcher = PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid version: " + raw);
        }
        return new ApiVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    public String asString() {
        return major + "." + minor;
    }

    public boolean sameMajor(ApiVersion other) {
        return other != null && major == other.major;
    }

    @Override
    public int compareTo(ApiVersion other) {
        int byMajor = Integer.compare(major, other.major);
        return byMajor != 0 ? byMajor : Integer.compare(minor, other.minor);
    }
}
