package com.scoresync.domain;

/**
 * 剪辑帧索引
 * 记录源视频逐帧的时间信息，帧序号从 0 开始，按 ffprobe 逐帧输出的顺序编号
 * <p>
 * 时间以微秒保存，避免可变帧率视频用平均帧率换算带来的误差。
 * 剪辑导出按盒时间窗口选帧与重排，因此导出的正确性不依赖行的顺序。
 * 该对象不可变，可安全地在多个请求间共享。
 */
public final class ClipFrameIndex {

    private final long[] ptsUs;
    private final long[] durationUs;
    private final long containerStartUs;

    /**
     * @param ptsUs            每帧的显示时间戳（微秒，容器原始时间轴）
     * @param durationUs       每帧的时长（微秒），与 ptsUs 等长
     * @param containerStartUs 容器起始时间（微秒），用于换算跳转位置
     */
    public ClipFrameIndex(long[] ptsUs, long[] durationUs, long containerStartUs) {
        if (ptsUs == null || ptsUs.length == 0) {
            throw new IllegalArgumentException("帧索引不能为空");
        }
        this.ptsUs = ptsUs.clone();
        this.durationUs = durationUs == null ? new long[0] : durationUs.clone();
        this.containerStartUs = containerStartUs;
    }

    /**
     * 总帧数
     */
    public int frameCount() {
        return ptsUs.length;
    }

    /**
     * 指定帧的显示时间戳（微秒，容器时间轴）
     */
    public long ptsUs(int frameNo) {
        return ptsUs[frameNo];
    }

    /**
     * 指定帧的时长（微秒）
     */
    public long durationUs(int frameNo) {
        if (frameNo < 0 || frameNo >= ptsUs.length) {
            return 0L;
        }
        if (durationUs.length == ptsUs.length && durationUs[frameNo] > 0) {
            return durationUs[frameNo];
        }
        if (frameNo + 1 < ptsUs.length) {
            return ptsUs[frameNo + 1] - ptsUs[frameNo];
        }
        return 0L;
    }

    /**
     * 指定帧用于 ffmpeg 跳转的时间（微秒）
     * ffmpeg 的 -ss 以容器起点为 0，因此要减去容器起始时间
     */
    public long seekUs(int frameNo) {
        return ptsUs(frameNo) - containerStartUs;
    }

    /**
     * 指定帧用于浏览器预览的时间（微秒）
     * 浏览器时间轴以第一帧为 0
     */
    public long previewUs(int frameNo) {
        return ptsUs(frameNo) - ptsUs[0];
    }

    /**
     * 区间右端点对应的时间（微秒，容器时间轴）
     * endFrameExclusive 等于总帧数时取最后一帧的结束时间
     */
    public long endTimeUs(int endFrameExclusive) {
        if (endFrameExclusive <= 0) {
            return 0L;
        }
        if (endFrameExclusive >= ptsUs.length) {
            int last = ptsUs.length - 1;
            return ptsUs[last] + durationUs(last);
        }
        return ptsUs[endFrameExclusive];
    }

    /**
     * 容器起始时间（微秒）
     */
    public long containerStartUs() {
        return containerStartUs;
    }

    /**
     * 整个视频的时长（微秒）
     */
    public long durationUs() {
        return endTimeUs(ptsUs.length);
    }
}
