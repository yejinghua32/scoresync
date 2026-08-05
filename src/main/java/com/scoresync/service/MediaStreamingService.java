package com.scoresync.service;

import com.scoresync.domain.VideoAsset;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.DomainNotFoundException;
import com.scoresync.support.DomainValidationException;
import com.scoresync.support.MediaUnavailableException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体流服务
 * 支持HTTP Range请求的视频流式传输，提供部分内容读取能力
 */
@Service
public class MediaStreamingService {

    private final VideoAssetRepository assetRepo;
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d+)-(\\d*)");

    public record StreamContent(InputStream body, long length, long start, long end, long totalLength) {
    }

    public MediaStreamingService(VideoAssetRepository assetRepo) {
        this.assetRepo = assetRepo;
    }

    /**
     * 打开视频流，支持HTTP Range请求
     *
     * @param videoId     视频资产ID
     * @param rangeHeader HTTP Range请求头（如"bytes=0-1023"）
     * @return 流内容对象，包含输入流、长度、起止位置等信息
     */
    public StreamContent open(long videoId, String rangeHeader) {
        VideoAsset asset = assetRepo.findById(videoId);
        if (asset == null) {
            throw new DomainNotFoundException("VideoAsset not found: " + videoId);
        }
        Path path;
        try {
            path = Path.of(asset.absolutePath()).toRealPath();
        } catch (IOException e) {
            throw new MediaUnavailableException("Media file no longer available: " + videoId);
        }
        long fileSize = asset.fileSize();
        if (fileSize <= 0) {
            throw new MediaUnavailableException("Video file is empty or size is invalid: " + videoId);
        }
        long start = 0;
        long end = fileSize - 1;
        if (rangeHeader != null && !rangeHeader.isEmpty()) {
            if (rangeHeader.contains(",")) {
                throw new DomainValidationException("不支持多范围Range请求");
            }
            Matcher m = RANGE_PATTERN.matcher(rangeHeader);
            if (!m.matches()) {
                throw new DomainValidationException("无效的Range请求格式");
            }
            start = Long.parseLong(m.group(1));
            String endRange = m.group(2);
            end = endRange.isEmpty() ? fileSize - 1 : Long.parseLong(endRange);
            if (end >= fileSize) {
                end = fileSize - 1;
            }
            if (start >= fileSize || start > end) {
                throw new MediaUnavailableException("Range请求超出文件范围");
            }
        }
        long length = end - start + 1;
        try {
            InputStream fileStream = Files.newInputStream(path);
            long toSkip = start;
            while (toSkip > 0) {
                long skipped = fileStream.skip(toSkip);
                if (skipped == 0) {
                    fileStream.read();
                    toSkip--;
                } else {
                    toSkip -= skipped;
                }
            }
            InputStream limitedStream = new LimitedInputStream(fileStream, length);
            return new StreamContent(limitedStream, length, start, end, fileSize);
        } catch (IOException e) {
            throw new MediaUnavailableException("无法打开媒体文件: " + videoId);
        }
    }

    /**
     * 限制读取长度的输入流包装器
     */
    private static class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b != -1) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(b, off, toRead);
            if (n != -1) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
