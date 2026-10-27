package com.scoresync.domain;

/**
 * 剪辑缩略图格式
 * 缩略图按该格式生成并缓存，缓存目录按格式版本隔离
 */
public enum ClipThumbnailFormat {
    /**
     * JPEG，体积远小于 PNG，适合缩略图预览
     */
    JPEG("jpg", "image/jpeg"),
    /**
     * PNG，无损但体积较大
     */
    PNG("png", "image/png");

    private final String extension;
    private final String mediaType;

    ClipThumbnailFormat(String extension, String mediaType) {
        this.extension = extension;
        this.mediaType = mediaType;
    }

    /**
     * 缓存文件扩展名（不含点）
     */
    public String extension() {
        return extension;
    }

    /**
     * HTTP 响应的 Content-Type
     */
    public String mediaType() {
        return mediaType;
    }

    /**
     * 按名称解析，未知名称回退到 JPEG
     */
    public static ClipThumbnailFormat parse(String value) {
        if (value == null || value.isBlank()) {
            return JPEG;
        }
        for (ClipThumbnailFormat format : values()) {
            if (format.name().equalsIgnoreCase(value.trim())) {
                return format;
            }
        }
        return JPEG;
    }
}
