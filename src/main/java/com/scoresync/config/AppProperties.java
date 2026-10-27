package com.scoresync.config;

/**
 * 应用配置属性
 * 通过 scoresync 前缀在 application.yml 中配置
 * 包含存储路径、编辑器设置、渲染参数等
 */

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scoresync")
public class AppProperties {

    @Value("${server.port:5574}")
    private int serverPort = 5574;

    public int getServerPort() {
        return serverPort;
    }

    private Storage storage = new Storage();
    private Editor editor = new Editor();
    private Render render = new Render();
    private Clip clip = new Clip();

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Clip getClip() {
        return clip;
    }

    public void setClip(Clip clip) {
        this.clip = clip;
    }

    public Editor getEditor() {
        return editor;
    }

    public void setEditor(Editor editor) {
        this.editor = editor;
    }

    public Render getRender() {
        return render;
    }

    public void setRender(Render render) {
        this.render = render;
    }

    /**
     * 存储配置
     */
    public static class Storage {
        private String dataDir = System.getProperty("user.home") + "/.scoresync";

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }
    }

    /**
     * 编辑器配置
     */
    public static class Editor {
        private int seekSeconds = 1;

        public int getSeekSeconds() {
            return seekSeconds;
        }

        public void setSeekSeconds(int seekSeconds) {
            this.seekSeconds = seekSeconds;
        }
    }

    /**
     * 渲染配置
     */
    public static class Render {
        private String ffmpegPath = "ffmpeg";
        private String ffprobePath = "ffprobe";
        private String outputDir = System.getProperty("user.home") + "/.scoresync/exports";
        private String workDir = System.getProperty("user.home") + "/.scoresync/render-work";
        private int videoCrf = 18;
        private String videoPreset = "medium";
        private String audioBitrate = "192k";

        public String getFfmpegPath() {
            return ffmpegPath;
        }

        public void setFfmpegPath(String ffmpegPath) {
            this.ffmpegPath = ffmpegPath;
        }

        public String getFfprobePath() {
            return ffprobePath;
        }

        public void setFfprobePath(String ffprobePath) {
            this.ffprobePath = ffprobePath;
        }

        public String getOutputDir() {
            return outputDir;
        }

        public void setOutputDir(String outputDir) {
            this.outputDir = outputDir;
        }

        public String getWorkDir() {
            return workDir;
        }

        public void setWorkDir(String workDir) {
            this.workDir = workDir;
        }

        public int getVideoCrf() {
            return videoCrf;
        }

        public void setVideoCrf(int videoCrf) {
            this.videoCrf = videoCrf;
        }

        public String getVideoPreset() {
            return videoPreset;
        }

        public void setVideoPreset(String videoPreset) {
            this.videoPreset = videoPreset;
        }

        public String getAudioBitrate() {
            return audioBitrate;
        }

        public void setAudioBitrate(String audioBitrate) {
            this.audioBitrate = audioBitrate;
        }
    }

    /**
     * 剪辑配置
     * 帧索引与缩略图缓存位于数据目录下，导出使用独立的工作目录
     */
    public static class Clip {
        private String cacheDir = System.getProperty("user.home") + "/.scoresync/clip-cache";
        private String workDir = System.getProperty("user.home") + "/.scoresync/clip-render-work";
        private int thumbnailWidth = 320;
        private int maxCachedThumbnails = 600;
        private int maxConcurrentThumbnailJobs = 2;
        private int thumbnailSeekFrames = 30;

        public String getCacheDir() {
            return cacheDir;
        }

        public void setCacheDir(String cacheDir) {
            this.cacheDir = cacheDir;
        }

        public String getWorkDir() {
            return workDir;
        }

        public void setWorkDir(String workDir) {
            this.workDir = workDir;
        }

        public int getThumbnailWidth() {
            return thumbnailWidth;
        }

        public void setThumbnailWidth(int thumbnailWidth) {
            this.thumbnailWidth = thumbnailWidth;
        }

        public int getMaxCachedThumbnails() {
            return maxCachedThumbnails;
        }

        public void setMaxCachedThumbnails(int maxCachedThumbnails) {
            this.maxCachedThumbnails = maxCachedThumbnails;
        }

        public int getMaxConcurrentThumbnailJobs() {
            return maxConcurrentThumbnailJobs;
        }

        public void setMaxConcurrentThumbnailJobs(int maxConcurrentThumbnailJobs) {
            this.maxConcurrentThumbnailJobs = maxConcurrentThumbnailJobs;
        }

        public int getThumbnailSeekFrames() {
            return thumbnailSeekFrames;
        }

        public void setThumbnailSeekFrames(int thumbnailSeekFrames) {
            this.thumbnailSeekFrames = thumbnailSeekFrames;
        }
    }
}
