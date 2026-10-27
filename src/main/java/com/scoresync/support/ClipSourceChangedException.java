package com.scoresync.support;

/**
 * 剪辑源文件已变化异常
 * 源视频的大小或修改时间与建立帧索引时不一致时抛出（409 Conflict）
 * 阻止把旧帧序号映射到新文件上
 */
public class ClipSourceChangedException extends RuntimeException {
    private final String code;

    public ClipSourceChangedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
