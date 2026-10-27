package com.scoresync.domain;

/**
 * 缩略图的尺寸与画质档位
 * <p>
 * 当前帧使用 large 档，画面更大更清晰；前后邻帧使用 small 档，传输量更小。
 *
 * @param name    档位名称
 * @param width   输出宽度（像素），高度按源视频比例推算
 * @param quality 有损格式的质量，数值越小画质越好
 * @param format  图片格式
 */
public record ClipThumbnailSpec(String name, int width, int quality, ClipThumbnailFormat format) {

    public ClipThumbnailSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("缩略图档位名称不能为空");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("缩略图宽度必须大于 0");
        }
        if (quality < 2 || quality > 31) {
            throw new IllegalArgumentException("缩略图质量必须在 2 到 31 之间");
        }
        if (format == null) {
            throw new IllegalArgumentException("缩略图格式不能为空");
        }
    }

    /**
     * 缓存目录中的档位名
     * 尺寸或画质变化后使用新的目录，旧目录会被自动回收
     */
    public String cacheKey() {
        return "w" + width + "q" + quality;
    }
}
