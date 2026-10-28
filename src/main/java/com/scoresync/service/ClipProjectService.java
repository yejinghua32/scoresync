package com.scoresync.service;

import com.scoresync.domain.ClipFrameIndexStatus;
import com.scoresync.domain.ClipProject;
import com.scoresync.domain.ClipProjectDetail;
import com.scoresync.domain.ClipSegment;
import com.scoresync.domain.ClipSegmentSpec;
import com.scoresync.domain.ClipSegmentType;
import com.scoresync.domain.VideoAsset;
import com.scoresync.repository.ClipProjectRepository;
import com.scoresync.repository.ClipSegmentRepository;
import com.scoresync.support.DomainNotFoundException;
import com.scoresync.support.DomainValidationException;
import com.scoresync.web.dto.PageRequest;
import com.scoresync.web.dto.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 剪辑项目服务
 * 管理剪辑项目的创建、配置与片段存档
 * <p>
 * 片段提交采用“覆盖”语义：新片段与已有片段重叠时，
 * 在同一个事务内拆分旧区间、写入新区间，并合并相邻的同类区间，
 * 因此区间始终互不重叠且覆盖语义确定
 */
@Service
public class ClipProjectService {

    public static final int DEFAULT_LARGE_STEP = 10;
    public static final int DEFAULT_SMALL_STEP = 5;

    private final ClipProjectRepository projectRepository;
    private final ClipSegmentRepository segmentRepository;
    private final ClipSourceValidator sourceValidator;

    /**
     * 片段提交锁
     * 串行化“读区间 → 覆盖 → 写回”，避免并发提交丢失区间
     */
    private final ReentrantLock commitLock = new ReentrantLock();

    public ClipProjectService(ClipProjectRepository projectRepository,
                              ClipSegmentRepository segmentRepository,
                              ClipSourceValidator sourceValidator) {
        this.projectRepository = projectRepository;
        this.segmentRepository = segmentRepository;
        this.sourceValidator = sourceValidator;
    }

    /**
     * 创建剪辑项目
     * 记录建立时的源文件大小与修改时间，作为后续检测源文件变化的依据
     */
    @Transactional
    public ClipProjectDetail create(String name, long videoAssetId) {
        if (name == null || name.isBlank()) {
            throw new DomainValidationException("剪辑项目名称不能为空");
        }
        VideoAsset asset = sourceValidator.requireAsset(videoAssetId);
        if (!java.nio.file.Files.isReadable(java.nio.file.Path.of(asset.absolutePath()))) {
            throw new com.scoresync.support.MediaUnavailableException("源视频文件不存在或不可读，无法创建剪辑项目");
        }
        long id = projectRepository.insert(name.trim(), asset.id(),
                DEFAULT_LARGE_STEP, DEFAULT_SMALL_STEP, asset.fileSize(), asset.modifiedAt());
        return detail(id);
    }

    /**
     * 列出全部剪辑项目
     */
    public List<ClipProjectDetail> list() {
        return projectRepository.findAll().stream().map(project -> detail(project.id())).toList();
    }

    /**
     * 分页列出剪辑项目
     * 可按项目名称或所属视频名称过滤
     *
     * @param page   页码，从 0 开始
     * @param size   每页数量
     * @param search 关键字，为空时不过滤
     */
    public PageResponse<ClipProjectDetail> listPage(int page, int size, String search) {
        PageRequest request = new PageRequest(page, size);
        int total = projectRepository.count(search);
        int totalPages = request.totalPages(total);
        int currentPage = request.page() >= totalPages && totalPages > 0 ? totalPages - 1 : request.page();
        List<ClipProjectDetail> items = projectRepository.findPage(currentPage * request.size(), request.size(), search)
                .stream()
                .map(project -> detail(project.id()))
                .toList();
        return new PageResponse<>(items, currentPage, request.size(), total, totalPages);
    }

    /**
     * 读取剪辑项目详情（含已存档片段）
     */
    public ClipProjectDetail detail(long projectId) {
        ClipProject project = projectRepository.findById(projectId);
        VideoAsset asset = sourceValidator.requireAsset(project.videoAssetId());
        List<ClipSegment> segments = segmentRepository.findByProjectId(projectId);
        return new ClipProjectDetail(project, asset, segments);
    }

    /**
     * 更新项目名称与两个步长
     */
    @Transactional
    public ClipProjectDetail updateConfig(long projectId, String name, Integer largeStep, Integer smallStep) {
        ClipProject project = projectRepository.findById(projectId);
        String newName = name == null || name.isBlank() ? project.name() : name.trim();
        int newLarge = largeStep == null ? project.largeStep() : largeStep;
        int newSmall = smallStep == null ? project.smallStep() : smallStep;
        if (newLarge <= 0) {
            throw new DomainValidationException("大步长必须是正整数");
        }
        if (newSmall <= 0) {
            throw new DomainValidationException("小步长必须是正整数");
        }
        projectRepository.updateConfig(new ClipProject(project.id(), newName, project.videoAssetId(),
                newLarge, newSmall, project.sourceFileSize(), project.sourceModifiedAt(),
                project.totalFrames(), project.frameIndexStatus(), project.createdAt(), project.updatedAt()));
        return detail(projectId);
    }

    /**
     * 提交片段（存档）
     * 新片段覆盖与其重叠的旧片段，旧片段被拆成左右两段保留，
     * 最后合并相邻的同类区间
     */
    @Transactional
    public List<ClipSegment> commitSegments(long projectId, List<ClipSegmentSpec> inputs) {
        ClipProject project = projectRepository.findById(projectId);
        if (inputs == null || inputs.isEmpty()) {
            throw new DomainValidationException("没有需要提交的片段");
        }
        if (project.frameIndexStatus() != ClipFrameIndexStatus.READY || project.totalFrames() == null) {
            throw new DomainValidationException("帧索引尚未建立完成，暂时无法提交片段");
        }
        int totalFrames = project.totalFrames();

        commitLock.lock();
        try {
            List<ClipSegment> merged = segmentRepository.findByProjectId(projectId);
            for (ClipSegmentSpec input : inputs) {
                validateSpec(input, totalFrames);
                merged = overlay(merged, input);
            }
            segmentRepository.deleteByProjectId(projectId);
            Instant now = Instant.now();
            for (ClipSegment segment : merged) {
                segmentRepository.insert(projectId, segment.type(), segment.startFrame(), segment.endFrame());
            }
            return segmentRepository.findByProjectId(projectId);
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * 删除一个已存档片段
     * 该区间的帧回到未标记状态，其余片段保持不变
     */
    @Transactional
    public void deleteSegment(long projectId, long segmentId) {
        projectRepository.findById(projectId);
        ClipSegment segment = segmentRepository.findById(segmentId);
        if (segment.projectId() != projectId) {
            throw new DomainNotFoundException("剪辑片段不存在");
        }
        commitLock.lock();
        try {
            segmentRepository.delete(segmentId);
        } finally {
            commitLock.unlock();
        }
    }

    /**
     * 删除剪辑项目及其片段
     */
    @Transactional
    public void delete(long projectId) {
        projectRepository.findById(projectId);
        segmentRepository.deleteByProjectId(projectId);
        projectRepository.delete(projectId);
    }

    /**
     * 校验片段输入的类别与范围
     */
    private void validateSpec(ClipSegmentSpec spec, int totalFrames) {
        if (spec == null || spec.type() == null) {
            throw new DomainValidationException("片段类型只能是 KEEP 或 DROP");
        }
        if (spec.startFrame() < 0) {
            throw new DomainValidationException("片段起始帧不能为负数");
        }
        if (spec.endFrame() <= spec.startFrame()) {
            throw new DomainValidationException("片段结束帧必须大于起始帧");
        }
        if (spec.endFrame() > totalFrames) {
            throw new DomainValidationException("片段超出视频帧范围（总帧数 " + totalFrames + "）");
        }
    }

    /**
     * 把新片段覆盖到已有区间之上，返回覆盖后的区间集合
     */
    private List<ClipSegment> overlay(List<ClipSegment> existing, ClipSegmentSpec input) {
        List<ClipSegment> result = new ArrayList<>();
        for (ClipSegment segment : existing) {
            boolean overlaps = segment.startFrame() < input.endFrame() && input.startFrame() < segment.endFrame();
            if (!overlaps) {
                result.add(segment);
                continue;
            }
            if (segment.startFrame() < input.startFrame()) {
                result.add(copy(segment, segment.startFrame(), input.startFrame()));
            }
            if (segment.endFrame() > input.endFrame()) {
                result.add(copy(segment, input.endFrame(), segment.endFrame()));
            }
        }
        result.add(new ClipSegment(0L, 0L, input.type(), input.startFrame(), input.endFrame(),
                Instant.now(), Instant.now()));
        result.sort(Comparator.comparingInt(ClipSegment::startFrame));

        List<ClipSegment> merged = new ArrayList<>();
        for (ClipSegment segment : result) {
            if (!merged.isEmpty()) {
                ClipSegment last = merged.get(merged.size() - 1);
                if (last.type() == segment.type() && last.endFrame() == segment.startFrame()) {
                    merged.set(merged.size() - 1, copy(last, last.startFrame(), segment.endFrame()));
                    continue;
                }
            }
            merged.add(segment);
        }
        return merged;
    }

    private ClipSegment copy(ClipSegment segment, int startFrame, int endFrame) {
        return new ClipSegment(segment.id(), segment.projectId(), segment.type(),
                startFrame, endFrame, segment.createdAt(), segment.updatedAt());
    }

    /**
     * 统计项目中保留片段的总帧数
     */
    public long keptFrameCount(long projectId) {
        return segmentRepository.findByProjectId(projectId).stream()
                .filter(segment -> segment.type() == ClipSegmentType.KEEP)
                .mapToLong(segment -> segment.endFrame() - segment.startFrame())
                .sum();
    }
}
