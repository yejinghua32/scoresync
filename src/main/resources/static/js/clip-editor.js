import {api, escapeHtml} from './api.js';

/**
 * 剪辑编辑器
 * 从上到下依次为：视频预览、重叠缩略图、保留帧区域、时间轴、舍弃帧区域
 *
 * 交互约定：
 * - ↑/↓ 设定当前暂选类型（保留/舍弃）并向后取大步长
 * - ↑/↓ 切换类型时自动存档上一段尚未存档的暂选
 * - → 追加小步长
 * - ← 从暂选末尾减少小步长
 * - Enter 存档暂选，类型不随之切换
 * 暂选只存在于前端内存，按 Enter 或切换类型时才持久化
 */

/** 左右两侧显示的帧偏移：当前帧的前/后第 3 帧 */
const STRIP_SIDE_OFFSET = 3;
/** 小尺寸缩略图：前后邻帧使用，传输量小 */
const SIZE_SMALL = 'small';
/** 大尺寸缩略图：当前帧使用，画面更清晰 */
const SIZE_LARGE = 'large';
/** 预取游标前方的小组帧数上限，只覆盖接下来的几步，不预先生成整段视频 */
const PREFETCH_AHEAD_MAX = 12;
/** 缩略图缓存保留的时间窗口（毫秒），以当前播放位置为中心 */
const THUMBNAIL_WINDOW_MS = 5000;
/** 缩略图缓存的字节上限，超出后按最近使用情况淘汰 */
const THUMBNAIL_MAX_BYTES = 32 * 1024 * 1024;
/** 同时进行的缩略图请求数上限 */
const THUMBNAIL_MAX_CONCURRENT = 3;
/** 播放中发起缩略图请求的最小间隔（毫秒），避免每次 timeupdate 都触发抽帧 */
const PLAYBACK_THUMBNAIL_INTERVAL_MS = 250;
/** 中间图区域轮播的间隔（毫秒），三张图像 GIF 一样依次播放 */
const STRIP_CYCLE_INTERVAL = 150;
/** 时间轴刻度的候选间隔（毫秒） */
const TICK_INTERVALS = [1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000,
    300000, 600000, 900000, 1800000, 3600000];
/** 显示时间刻度标签所需的最小间距（像素） */
const TICK_LABEL_MIN_SPACING = 52;

let project = null;
let segments = [];
let totalFrames = 0;
let largeStep = 10;
let smallStep = 5;

/** 当前游标所在帧 */
let cursor = 0;
/** 暂选区间 {start, end, type}，未存档 */
let pending = null;
/** 正在进行的存档请求，非空时复用同一个请求，避免连按造成重复提交 */
let archiveInFlight = null;
/** 缩略图缓存 "帧号:尺寸" -> {url, bytes, timeMs, lastUsed} */
const thumbnails = new Map();
/** 缩略图缓存占用的字节数 */
let thumbnailBytes = 0;
/** 正在进行的缩略图请求 "帧号:尺寸" -> {controller, priority, promise} */
const thumbnailRequests = new Map();
/** 等待发出的缩略图请求 */
const thumbnailQueue = [];
/** 正在进行的请求数 */
let thumbnailActive = 0;
/** 缓存代次，释放缓存后旧请求的结果不再写回 */
let thumbnailEpoch = 0;
/** 上一次发起缩略图请求的时间 */
let lastThumbnailRequestAt = 0;
/** 中间图区域的轮播状态 */
let stripCycleTimer = null;
let stripCycleFrames = [];
let stripCycleIndex = 0;
/** 帧号 -> 源视频时间（毫秒） */
let frameTimes = new Int32Array(0);
/** 导出任务轮询句柄 */
let renderPollTimer = null;
/** 跳转完成后需要恢复的帧号，非空时暂停游标跟随 */
let seekRestoreFrame = null;
/** 用户是否手动定位过视频，手动定位期间跳播预览让位 */
let manualPosition = false;

const video = document.getElementById('clip-video');
const timeline = document.getElementById('clip-timeline');
const strip = document.getElementById('clip-strip');
const cursorMarker = document.getElementById('clip-timeline-cursor');

document.addEventListener('DOMContentLoaded', init);

async function init() {
    const params = new URLSearchParams(location.search);
    const projectId = Number(params.get('projectId'));
    if (!projectId) {
        showToast('缺少剪辑项目 ID', 'error');
        return;
    }
    bindEvents();
    await loadProject(projectId);
}

function bindEvents() {
    document.addEventListener('keydown', onKeyDown);
    timeline.addEventListener('click', onTimelineClick);
    document.getElementById('clip-segment-list').addEventListener('click', onSegmentClick);
    document.getElementById('clip-save-config').addEventListener('click', saveConfig);
    document.getElementById('clip-export').addEventListener('click', startExport);
    document.getElementById('clip-cancel-export').addEventListener('click', cancelExport);
    video.addEventListener('timeupdate', onTimeUpdate);
    video.addEventListener('seeked', onSeeked);
    // 视频尺寸就绪后按真实比例重排缩略图条
    video.addEventListener('loadedmetadata', renderStrip);
    window.addEventListener('resize', () => {
        renderTimeline();
        renderStrip();
    });
    window.addEventListener('beforeunload', releaseThumbnails);
    // 原生播放器控件拿到焦点后浏览器不再把按键事件派发到页面，
    // 剪辑快捷键会整体失灵；焦点回到页面后即可恢复。
    document.addEventListener('focusin', (event) => {
        if (event.target === video) {
            video.blur();
        }
    });
}

async function loadProject(projectId) {
    try {
        releaseThumbnails();
        const data = await api(`/api/clip-projects/${projectId}`);
        project = data;
        segments = data.segments || [];
        totalFrames = data.totalFrames || 0;
        largeStep = data.largeStep || 10;
        smallStep = data.smallStep || 5;

        document.getElementById('clip-title').textContent = `剪辑：${data.name}`;
        document.getElementById('clip-name').value = data.name;
        document.getElementById('clip-large-step').value = largeStep;
        document.getElementById('clip-small-step').value = smallStep;
        video.src = `/api/media/${data.videoAssetId}`;
        video.load();

        renderSegments();
        renderTimeline();
        updateFrameInfo();
        updateExportAvailability();
        await ensureFrameIndex(projectId);
    } catch (e) {
        showToast(e.message, 'error');
    }
}

/**
 * 确保帧索引就绪
 * 索引在后台建立，这里轮询状态
 */
async function ensureFrameIndex(projectId) {
    const state = document.getElementById('clip-index-state');
    for (let attempt = 0; attempt < 600; attempt++) {
        const status = await api(`/api/clip-projects/${projectId}/frame-index`);
        if (status.status === 'READY') {
            totalFrames = status.totalFrames || totalFrames;
            state.className = 'clip-index-state ready';
            state.textContent = '';
            await loadFrameTimes();
            updateFrameInfo();
            updateExportAvailability();
            // 帧时间就绪后重算片段时长与时间轴刻度
            renderSegments();
            renderTimeline();
            refreshStrip();
            return;
        }
        if (status.status === 'FAILED') {
            state.className = 'clip-index-state failed';
            state.innerHTML = '帧索引建立失败，无法继续剪辑。<button class="secondary" id="clip-retry-index">重试</button>';
            document.getElementById('clip-retry-index').addEventListener('click', () => {
                state.textContent = '正在重新建立帧索引…';
                ensureFrameIndex(projectId);
            });
            return;
        }
        state.className = 'clip-index-state loading';
        state.textContent = status.status === 'BUILDING'
            ? '正在建立帧索引，请稍候…'
            : '正在准备帧索引…';
        await sleep(500);
    }
    state.className = 'clip-index-state failed';
    state.textContent = '帧索引建立超时，请刷新页面重试。';
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * 载入帧号到时间的映射
 * 一次取回完整映射，之后用二分查找换算位置
 */
async function loadFrameTimes() {
    try {
        const data = await api(`/api/clip-projects/${project.id}/frame-times`);
        frameTimes = new Int32Array(data.times || []);
    } catch (e) {
        frameTimes = new Int32Array(0);
    }
}

/**
 * 帧号对应的播放时间（毫秒）
 */
function frameTimeMs(frameNo) {
    if (frameTimes.length && frameNo >= 0 && frameNo < frameTimes.length) {
        return frameTimes[frameNo];
    }
    // 映射尚未载入时按帧号线性估算，仅用于界面显示
    return Math.round(frameNo * 1000 / 30);
}

/**
 * 播放时间对应的帧号
 * 用二分查找定位最接近该时间的帧
 */
function nearestFrame(currentMs) {
    if (!frameTimes.length) {
        return cursor;
    }
    let low = 0;
    let high = frameTimes.length - 1;
    while (low < high) {
        const mid = (low + high) >> 1;
        if (frameTimes[mid] < currentMs) {
            low = mid + 1;
        } else {
            high = mid;
        }
    }
    // low 是首个不早于目标时间的帧，再与前一帧比较取更接近者
    if (low > 0 && Math.abs(frameTimes[low - 1] - currentMs) <= Math.abs(frameTimes[low] - currentMs)) {
        return low - 1;
    }
    return low;
}

/**
 * 键盘操作
 */
function onKeyDown(e) {
    // 输入控件获焦时不劫持方向键与回车
    const target = e.target;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT')) {
        return;
    }

    switch (e.key) {
        case 'ArrowUp':
            e.preventDefault();
            stepSelection('KEEP');
            break;
        case 'ArrowDown':
            e.preventDefault();
            stepSelection('DROP');
            break;
        case 'ArrowRight':
            e.preventDefault();
            if (pending) {
                extendPending(smallStep);
            } else {
                moveCursor(smallStep);
            }
            break;
        case 'ArrowLeft':
            e.preventDefault();
            if (pending) {
                shrinkPending(smallStep);
            } else {
                moveCursor(-smallStep);
            }
            break;
        case 'Enter':
            e.preventDefault();
            commitPending();
            break;
        case ' ':
            e.preventDefault();
            togglePlay();
            break;
        default:
            break;
    }
}

/**
 * ↑/↓：设定暂选类型并向后取大步长
 * 与当前暂选类型不同即为切换，切换前先把尚未存档的暂选存档
 */
async function stepSelection(type) {
    if (totalFrames <= 0 || archiveInFlight) {
        return;
    }
    if (pending && pending.type !== type) {
        const saved = await archivePending('已自动存档上一段暂选');
        if (!saved) {
            // 存档失败时保留暂选，避免丢帧
            return;
        }
    }
    if (!pending) {
        pending = {start: cursor, end: cursor, type};
    } else {
        pending.type = type;
    }
    const step = Math.max(1, largeStep);
    pending.end = Math.min(totalFrames, pending.end + step);
    // 游标推进到下一帧，末尾按总帧数截断
    cursor = Math.min(totalFrames, pending.end);
    renderPending();
    updateFrameInfo();
    seekVideoToFrame(cursor);
    refreshStrip();
}

/**
 * →：追加小步长
 */
function extendPending(step) {
    if (!pending || totalFrames <= 0) {
        return;
    }
    pending.end = Math.min(totalFrames, pending.end + Math.max(1, step));
    cursor = pending.end;
    renderPending();
    updateFrameInfo();
    seekVideoToFrame(cursor);
    refreshStrip();
}

/**
 * ←：从暂选末尾减少小步长，最多回退到暂选起点
 */
function shrinkPending(step) {
    if (!pending) {
        return;
    }
    pending.end = Math.max(pending.start, pending.end - Math.max(1, step));
    if (pending.end <= pending.start) {
        pending = null;
    } else {
        cursor = pending.end;
    }
    renderPending();
    updateFrameInfo();
    seekVideoToFrame(cursor);
    refreshStrip();
}

/**
 * 没有暂选时用左右键移动当前帧
 * 移动后视频同步跳到该帧，步长与暂选的增减一致
 */
function moveCursor(step) {
    if (totalFrames <= 0) {
        return;
    }
    const target = Math.min(totalFrames - 1, Math.max(0, cursor + step));
    if (target === cursor) {
        return;
    }
    moveCursorTo(target);
}

/**
 * 把当前帧移动到指定帧
 * 视频同步定位到该帧对应的时间
 */
function moveCursorTo(frame) {
    if (totalFrames <= 0) {
        return;
    }
    const target = Math.min(totalFrames - 1, Math.max(0, frame));
    cursor = target;
    seekVideoToFrame(target);
    updateFrameInfo();
    refreshStrip();
}

/**
 * 让视频定位到指定帧的时间
 * 跳转期间暂停游标跟随，避免浏览器实际落点回写覆盖用户选中的帧；
 * 定位后跳播预览让位给用户选择的位置，直到用户再次播放
 */
function seekVideoToFrame(frame) {
    if (!totalFrames) {
        return;
    }
    const target = Math.min(totalFrames - 1, Math.max(0, frame));
    const seconds = frameTimeMs(target) / 1000;
    if (Math.abs(video.currentTime - seconds) < 0.001) {
        // 已经停在这一帧，不需要跳转
        seekRestoreFrame = null;
        return;
    }
    seekRestoreFrame = frame;
    manualPosition = true;
    video.currentTime = seconds;
}

/**
 * Enter：存档暂选，类型不随之切换
 */
async function commitPending() {
    if (archiveInFlight) {
        return;
    }
    if (!pending || pending.end <= pending.start) {
        showToast('没有可存档的暂选', 'warning');
        return;
    }
    await archivePending('片段已存档');
}

/**
 * 存档当前暂选
 * 同时只允许一个存档请求在途，连按时复用同一个请求
 * @param {string} message 存档成功后的提示文案
 * @returns {Promise<boolean>} 是否存档成功，没有可存档的暂选也算成功
 */
function archivePending(message) {
    if (archiveInFlight) {
        return archiveInFlight;
    }
    archiveInFlight = doArchivePending(message).finally(() => {
        archiveInFlight = null;
    });
    return archiveInFlight;
}

async function doArchivePending(message) {
    if (!pending || pending.end <= pending.start) {
        pending = null;
        renderPending();
        return true;
    }
    const segment = {type: pending.type, startFrame: pending.start, endFrame: pending.end};
    try {
        const updated = await api(`/api/clip-projects/${project.id}/segments`, {
            method: 'POST',
            body: JSON.stringify({segments: [segment]})
        });
        segments = updated.segments || [];
        project = {...project, segments};
        pending = null;
        renderSegments();
        renderPending();
        renderTimeline();
        updateFrameInfo();
        updateExportAvailability();
        showToast(message, 'success');
        return true;
    } catch (e) {
        showToast(e.message, 'error');
        return false;
    }
}

function togglePlay() {
    if (video.paused) {
        // 再次播放时跳播预览重新接管
        manualPosition = false;
        video.play();
    } else {
        video.pause();
    }
    // 播放与暂停时中间图区域的轮播随之开关
    refreshStrip();
}

/**
 * 点击时间轴跳到任意源帧
 */
function onTimelineClick(e) {
    if (totalFrames <= 0) {
        return;
    }
    const rect = timeline.getBoundingClientRect();
    const ratio = Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 0.9999);
    // 跳到新位置后暂选从当前位置重新开始
    pending = null;
    moveCursorTo(Math.min(totalFrames - 1, Math.floor(ratio * totalFrames)));
    renderPending();
}

/**
 * 点击已存档片段：定位到该片段的最后一帧
 * 片段是半开区间 [startFrame, endFrame)，最后一帧为 endFrame - 1
 */
function onSegmentClick(e) {
    const removeButton = e.target.closest('.clip-segment-delete');
    if (removeButton) {
        deleteSegment(Number(removeButton.dataset.id));
        return;
    }
    const item = e.target.closest('.clip-segment-item');
    if (!item) {
        return;
    }
    const start = Number(item.dataset.start);
    const end = Number(item.dataset.end);
    if (!Number.isFinite(start) || !Number.isFinite(end)) {
        return;
    }
    pending = null;
    moveCursorTo(Math.max(start, end - 1));
    renderPending();
}

/**
 * 删除一个已存档片段
 * 该区间的帧回到未标记状态
 */
async function deleteSegment(segmentId) {
    if (!project || !Number.isFinite(segmentId)) {
        return;
    }
    const segment = segments.find(item => item.id === segmentId);
    if (!segment) {
        return;
    }
    if (!confirm(`确定删除片段「${segment.startFrame} - ${segment.endFrame}」吗？该区间的帧会回到未标记状态。`)) {
        return;
    }
    try {
        const updated = await api(`/api/clip-projects/${project.id}/segments/${segmentId}`, {method: 'DELETE'});
        segments = updated.segments || [];
        project = {...project, segments};
        renderSegments();
        renderTimeline();
        updateFrameInfo();
        updateExportAvailability();
        showToast('片段已删除', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
}

/**
 * 播放时同步游标
 */
function onTimeUpdate() {
    if (seekRestoreFrame !== null) {
        return;
    }
    if (!video.duration || !isFinite(video.duration)) {
        return;
    }
    const currentMs = video.currentTime * 1000;
    const frame = nearestFrame(currentMs);
    if (frame !== cursor) {
        cursor = frame;
        updateFrameInfo();
        refreshStrip();
    }
    enforceKeepPlayback();
}

/**
 * 跳播预览：只遍历已存档的保留片段
 * 浏览器直接在原视频中跳播，交界处可能与导出略有差异
 * 仅在播放状态下生效，暂停编辑时可以在任意位置定位
 */
function enforceKeepPlayback() {
    if (video.paused) {
        return;
    }
    if (manualPosition) {
        // 用户刚手动定位过，跳播预览先让位，直到用户再次播放
        return;
    }
    const keeps = keepSegments();
    if (!keeps.length) {
        return;
    }
    const currentMs = video.currentTime * 1000;
    const inKeep = keeps.some(segment => {
        const start = frameTimeMs(segment.startFrame);
        const end = frameTimeMs(segment.endFrame);
        return currentMs >= start - 1 && currentMs < end;
    });
    if (inKeep) {
        return;
    }
    // 播到保留片段之外时跳到下一个保留片段；没有下一个则回到第一个
    const next = keeps.find(segment => frameTimeMs(segment.startFrame) > currentMs);
    const target = next ? next : keeps[0];
    video.currentTime = frameTimeMs(target.startFrame) / 1000;
}

function onSeeked() {
    if (seekRestoreFrame !== null) {
        cursor = seekRestoreFrame;
        seekRestoreFrame = null;
        updateFrameInfo();
        refreshStrip();
        return;
    }
    enforceKeepPlayback();
}

function keepSegments() {
    return segments.filter(segment => segment.type === 'KEEP');
}

/**
 * 渲染已存档片段
 */
function renderSegments() {
    renderSegmentList();

    const keeps = keepSegments();
    const keptFrames = keeps.reduce((sum, segment) => sum + (segment.endFrame - segment.startFrame), 0);
    const keptMs = keeps.reduce((sum, segment) => sum + frameRangeMs(segment.startFrame, segment.endFrame), 0);
    const summary = document.getElementById('clip-keep-summary');
    if (!totalFrames) {
        summary.textContent = '';
        return;
    }
    summary.textContent = `保留 ${keptFrames} 帧 / ${formatDuration(keptMs)} · 视频总时长 ${formatDuration(frameRangeMs(0, totalFrames))}`;
}

/**
 * 时长显示文本
 */
function formatDuration(ms) {
    return `${(Math.max(0, ms) / 1000).toFixed(3)} 秒`;
}

/**
 * 时间轴：同时显示保留、舍弃与未标记三种状态
 */
function renderTimeline() {
    if (!totalFrames) {
        timeline.innerHTML = '';
        renderTimelineRuler();
        return;
    }
    const bars = segments.map(segment => {
        const left = (segment.startFrame / totalFrames) * 100;
        const width = ((segment.endFrame - segment.startFrame) / totalFrames) * 100;
        return `<div class="clip-timeline-bar ${segment.type === 'KEEP' ? 'keep' : 'drop'}"
                     style="left:${left}%;width:${width}%"
                     title="${segment.type} ${segment.startFrame}-${segment.endFrame}"></div>`;
    }).join('');
    // 未标记帧使用中性底色，与保留、舍弃明确区分
    timeline.innerHTML = `<div class="clip-timeline-unmarked"></div>${bars}`;
    updateCursorMarker();
    renderTimelineRuler();
}

/**
 * 时间轴刻度
 * 刻度间隔按可用宽度挑选，放得下时才显示加粗的时间标签
 */
function renderTimelineRuler() {
    const ruler = document.getElementById('clip-timeline-ruler');
    if (!ruler) {
        return;
    }
    const totalMs = totalFrames > 0 ? frameRangeMs(0, totalFrames) : 0;
    const width = ruler.clientWidth;
    if (totalMs <= 0 || width <= 0) {
        ruler.innerHTML = '';
        return;
    }
    const interval = pickTickInterval(totalMs, width);
    const spacing = (interval / totalMs) * width;
    const ticks = [];
    for (let time = 0; time <= totalMs; time += interval) {
        const left = (time / totalMs) * 100;
        const position = time === 0 ? ' first' : (time + interval > totalMs ? ' last' : '');
        ticks.push({time, left, position});
    }
    // 间距不够时只画刻度线，避免标签互相压住
    ruler.innerHTML = buildTicks(ticks, spacing >= TICK_LABEL_MIN_SPACING);
    if (spacing >= TICK_LABEL_MIN_SPACING && rulerLabelsOverlap(ruler)) {
        ruler.innerHTML = buildTicks(ticks, false);
    }
}

/**
 * 生成刻度
 */
function buildTicks(ticks, labelled) {
    return ticks.map(tick => `<span class="clip-tick${labelled ? ' labelled' : ''}${tick.position}" style="left:${tick.left}%">
            <span class="clip-tick-line"></span>
            ${labelled ? `<span class="clip-tick-label">${formatTickTime(tick.time)}</span>` : ''}
        </span>`).join('');
}

/**
 * 刻度标签是否互相压住
 */
function rulerLabelsOverlap(ruler) {
    const boxes = Array.from(ruler.querySelectorAll('.clip-tick-label'))
        .map(label => label.getBoundingClientRect());
    for (let i = 1; i < boxes.length; i++) {
        if (boxes[i].left < boxes[i - 1].right) {
            return true;
        }
    }
    return false;
}

/**
 * 挑选刻度间隔
 * 取能让相邻刻度留出足够间距的最小间隔
 */
function pickTickInterval(totalMs, width) {
    for (const interval of TICK_INTERVALS) {
        if ((interval / totalMs) * width >= TICK_LABEL_MIN_SPACING) {
            return interval;
        }
    }
    return TICK_INTERVALS[TICK_INTERVALS.length - 1];
}

/**
 * 刻度上的时间标签
 * 不到一小时显示分秒，超过一小时带上小时
 */
function formatTickTime(ms) {
    const total = Math.floor(ms / 1000);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    if (hours > 0) {
        return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
    }
    return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function renderSegmentList() {
    const list = document.getElementById('clip-segment-list');
    if (!segments.length) {
        list.innerHTML = '<div class="empty-tip">暂无已存档片段</div>';
        return;
    }
    list.innerHTML = segments.map(segment => `
    <div class="clip-segment-item ${segment.type === 'KEEP' ? 'keep' : 'drop'}"
         data-id="${segment.id}" data-start="${segment.startFrame}" data-end="${segment.endFrame}"
         title="点击定位到该片段的最后一帧">
      <span class="clip-segment-type">${segment.type === 'KEEP' ? '保留' : '舍弃'}</span>
      <span class="clip-segment-range">${segment.startFrame} - ${segment.endFrame}</span>
      <span class="clip-segment-count">${segment.endFrame - segment.startFrame} 帧 / ${formatDuration(frameRangeMs(segment.startFrame, segment.endFrame))}</span>
      <button class="clip-segment-delete" data-id="${segment.id}" title="删除该片段">删除</button>
    </div>`).join('');
}

function updateCursorMarker() {
    if (!totalFrames) {
        return;
    }
    const ratio = Math.min(cursor / totalFrames, 1);
    cursorMarker.style.left = `${ratio * 100}%`;
}

/**
 * 刷新缩略图条
 * 先重绘当前可见的帧，再按优先级安排还没取到的缩略图
 */
function refreshStrip() {
    renderStrip();
    pruneThumbnails();
    scheduleThumbnails();
    refreshStripCycle();
}

/**
 * 渲染缩略图条
 * 只显示三张：中间是当前帧，左边是前第 3 帧，右边是后第 3 帧
 */
function renderStrip() {
    if (!totalFrames) {
        strip.innerHTML = '';
        return;
    }
    // 固定三个位置：左侧、当前帧、右侧
    // 缺少的一侧保留空位，当前帧始终居中
    const layout = stripLayout();
    strip.innerHTML = [cursor - STRIP_SIDE_OFFSET, cursor, cursor + STRIP_SIDE_OFFSET].map(frame => {
        const isCurrent = frame === cursor;
        const width = isCurrent ? layout.currentWidth : layout.sideWidth;
        const height = isCurrent ? layout.currentHeight : layout.sideHeight;
        if (frame < 0 || frame > totalFrames - 1) {
            return `<div class="clip-strip-item empty" style="width:${width}px;height:${height}px"></div>`;
        }
        const cached = bestThumbnail(frame);
        const image = cached
            ? `<img src="${cached}" alt="第${frame}帧">`
            : `<div class="clip-strip-placeholder"></div>`;
        return `<div class="clip-strip-item ${isCurrent ? 'current' : ''}"
                     data-frame="${frame}"
                     style="width:${width}px;height:${height}px">${image}</div>`;
    }).join('');
}

/**
 * 中间图区域轮播
 * 三张图都加载完成后开始轮播，像 GIF 一样依次显示；
 * 播放中或图片未就位时暂停
 */
function refreshStripCycle() {
    const frames = [cursor - STRIP_SIDE_OFFSET, cursor, cursor + STRIP_SIDE_OFFSET]
        .filter(frame => frame >= 0 && frame <= totalFrames - 1);
    const ready = frames.length === 3 && frames.every(frame => bestThumbnail(frame));
    if (!ready || !video.paused) {
        stopStripCycle();
        return;
    }
    if (stripCycleTimer && stripCycleFrames.join() === frames.join()) {
        return;
    }
    stopStripCycle();
    stripCycleFrames = frames;
    stripCycleIndex = frames.indexOf(cursor);
    stripCycleTimer = setInterval(() => {
        stripCycleIndex = (stripCycleIndex + 1) % stripCycleFrames.length;
        showMiddleFrame(stripCycleFrames[stripCycleIndex]);
    }, STRIP_CYCLE_INTERVAL);
}

/**
 * 停止轮播
 */
function stopStripCycle() {
    if (stripCycleTimer) {
        clearInterval(stripCycleTimer);
        stripCycleTimer = null;
    }
    stripCycleFrames = [];
    stripCycleIndex = 0;
}

/**
 * 中间图区域显示指定帧
 */
function showMiddleFrame(frame) {
    const item = strip.querySelector('.clip-strip-item.current');
    if (!item) {
        return;
    }
    const url = bestThumbnail(frame);
    if (!url) {
        return;
    }
    const image = item.querySelector('img');
    if (image && image.getAttribute('src') === url) {
        return;
    }
    item.innerHTML = `<img src="${url}" alt="第${frame}帧">`;
}

/**
 * 当前关注的帧与对应尺寸
 * 三张可见帧加上游标前方的一小组预取帧；
 * 当前帧与下一步会到达的帧用大尺寸，画面更清晰
 */
function wantedFrames() {
    const frames = new Map();
    if (!totalFrames) {
        return frames;
    }
    for (let frame = Math.max(0, cursor - STRIP_SIDE_OFFSET); frame <= Math.min(totalFrames - 1, cursor + STRIP_SIDE_OFFSET); frame++) {
        frames.set(frame, SIZE_SMALL);
    }
    for (let frame = cursor + 1; frame <= Math.min(totalFrames - 1, cursor + prefetchAhead()); frame++) {
        frames.set(frame, SIZE_SMALL);
    }
    frames.set(cursor, SIZE_LARGE);
    const nextFrame = Math.min(totalFrames - 1, cursor + Math.max(1, smallStep));
    if (nextFrame > cursor) {
        // 下一步会到达的帧提前按大尺寸取好，步进时无需再抽帧
        frames.set(nextFrame, SIZE_LARGE);
    }
    return frames;
}

/**
 * 预取帧数
 * 覆盖下一步移动之后可见的窗口，使连续步进不需要新的抽帧
 */
function prefetchAhead() {
    return Math.min(PREFETCH_AHEAD_MAX, STRIP_SIDE_OFFSET + Math.max(1, smallStep));
}

/**
 * 请求优先级，数值越小越先发出
 */
function thumbnailPriority(frame) {
    if (frame === cursor) {
        return 0;
    }
    return Math.abs(frame - cursor) <= STRIP_SIDE_OFFSET ? 1 : 2;
}

/**
 * 安排缩略图加载
 * 当前帧优先，其次可见邻帧，最后是游标前方的预取帧
 */
function scheduleThumbnails() {
    if (!totalFrames || !project) {
        return;
    }
    const wanted = wantedFrames();
    if (!video.paused) {
        // 播放中只按当前帧请求并限制频率，避免每次 timeupdate 都触发抽帧
        const now = Date.now();
        if (now - lastThumbnailRequestAt < PLAYBACK_THUMBNAIL_INTERVAL_MS) {
            return;
        }
        lastThumbnailRequestAt = now;
        wanted.clear();
        wanted.set(cursor, SIZE_LARGE);
    }
    const wantedKeys = new Set(Array.from(wanted, ([frame, size]) => thumbnailKey(frame, size)));

    // 放弃已经不需要的请求，旧位置的预取不占用新位置的名额
    cancelStaleRequests(wantedKeys);

    // 丢弃已经不需要的排队请求，队列不会随播放持续增长
    for (let i = thumbnailQueue.length - 1; i >= 0; i--) {
        if (!wantedKeys.has(thumbnailQueue[i].key)) {
            thumbnailQueue.splice(i, 1);
        }
    }

    const pending = [];
    for (const [frame, size] of wanted) {
        const key = thumbnailKey(frame, size);
        if (thumbnails.has(key) || thumbnailRequests.has(key)) {
            continue;
        }
        if (thumbnailQueue.some(item => item.key === key)) {
            continue;
        }
        pending.push({frame, size, key});
    }
    pending.sort((a, b) => thumbnailPriority(a.frame) - thumbnailPriority(b.frame)
        || Math.abs(a.frame - cursor) - Math.abs(b.frame - cursor));
    for (const item of pending) {
        thumbnailQueue.push(item);
    }
    pumpThumbnailQueue();
}

/**
 * 取消已经不需要的请求
 * 游标移动后，旧位置的请求立即让出名额给新的当前帧
 */
function cancelStaleRequests(wantedKeys) {
    for (const [key, entry] of Array.from(thumbnailRequests)) {
        if (wantedKeys.has(key)) {
            continue;
        }
        entry.controller.abort();
    }
}

/**
 * 发出排队中的请求
 * 优先级在发出时按当前游标重新计算，游标移动后当前帧立刻排在最前
 */
function pumpThumbnailQueue() {
    while (thumbnailActive < THUMBNAIL_MAX_CONCURRENT && thumbnailQueue.length) {
        let best = 0;
        for (let i = 1; i < thumbnailQueue.length; i++) {
            if (thumbnailPriority(thumbnailQueue[i].frame) < thumbnailPriority(thumbnailQueue[best].frame)) {
                best = i;
            }
        }
        const item = thumbnailQueue.splice(best, 1)[0];
        startThumbnailRequest(item.frame, item.size);
    }
}

/**
 * 加载一帧缩略图
 * 同一帧同一尺寸只保留一个进行中的请求，失败后可以重试
 */
function startThumbnailRequest(frame, size) {
    const key = thumbnailKey(frame, size);
    const priority = frame === cursor ? 'foreground' : 'background';
    const controller = new AbortController();
    const epoch = thumbnailEpoch;
    thumbnailActive++;
    const entry = {controller, priority};
    const request = (async () => {
        try {
            const response = await fetch(
                `/api/clip-projects/${project.id}/frames/${frame}/thumbnail?size=${size}`, {
                    headers: {'X-Thumbnail-Priority': priority},
                    signal: controller.signal
                });
            if (!response.ok) {
                return null;
            }
            return await response.blob();
        } catch (e) {
            // 缩略图缺失或请求被取消，不影响主要流程
            return null;
        }
    })();
    entry.promise = request;
    thumbnailRequests.set(key, entry);
    request.then(blob => {
        if (thumbnailRequests.get(key) === entry) {
            thumbnailRequests.delete(key);
        }
        thumbnailActive--;
        if (epoch === thumbnailEpoch && blob && blob.size > 0) {
            storeThumbnail(frame, size, blob);
            applyThumbnail(frame);
        }
        pumpThumbnailQueue();
    });
}

/**
 * 缩略图缓存的键
 * 同一帧可以有两种尺寸，分别缓存
 */
function thumbnailKey(frame, size) {
    return frame + ':' + size;
}

/**
 * 取某一帧可用的缩略图地址
 * 当前帧优先用大尺寸，邻帧用小尺寸；另一种尺寸也可以临时顶上
 */
function bestThumbnail(frame) {
    const prefer = frame === cursor ? SIZE_LARGE : SIZE_SMALL;
    const fallback = prefer === SIZE_LARGE ? SIZE_SMALL : SIZE_LARGE;
    const entry = thumbnails.get(thumbnailKey(frame, prefer))
        || thumbnails.get(thumbnailKey(frame, fallback));
    if (!entry) {
        return null;
    }
    entry.lastUsed = Date.now();
    return entry.url;
}

/**
 * 存入缩略图并淘汰超出预算的旧图
 */
function storeThumbnail(frame, size, blob) {
    const key = thumbnailKey(frame, size);
    const previous = thumbnails.get(key);
    if (previous) {
        thumbnailBytes -= previous.bytes;
        URL.revokeObjectURL(previous.url);
    }
    const entry = {
        url: URL.createObjectURL(blob),
        bytes: blob.size,
        frame,
        timeMs: frameTimeMs(frame),
        lastUsed: Date.now()
    };
    thumbnails.set(key, entry);
    thumbnailBytes += entry.bytes;
    pruneThumbnails();
}

/**
 * 缩略图就位后只更新对应的那一张，不重绘整条缩略图条
 */
function applyThumbnail(frame) {
    const item = strip.querySelector(`.clip-strip-item[data-frame="${frame}"]`);
    if (!item) {
        return;
    }
    const url = bestThumbnail(frame);
    if (!url) {
        return;
    }
    const image = item.querySelector('img');
    if (image && image.getAttribute('src') === url) {
        refreshStripCycle();
        return;
    }
    item.innerHTML = `<img src="${url}" alt="第${frame}帧">`;
    refreshStripCycle();
}

/**
 * 淘汰缩略图
 * 先按当前播放位置附近的时间窗口淘汰，再按字节上限按最近使用情况淘汰
 */
function pruneThumbnails() {
    if (!thumbnails.size) {
        return;
    }
    const pinned = pinnedFrames();
    const currentMs = frameTimeMs(cursor);
    for (const [key, entry] of Array.from(thumbnails)) {
        if (pinned.has(entry.frame)) {
            continue;
        }
        // 窗口按帧在源视频中的时间计算，可变帧率视频同样适用
        if (frameTimes.length && Math.abs(entry.timeMs - currentMs) > THUMBNAIL_WINDOW_MS) {
            dropThumbnail(key);
        }
    }
    while (thumbnailBytes > THUMBNAIL_MAX_BYTES) {
        let oldest = null;
        for (const [key, entry] of thumbnails) {
            if (pinned.has(entry.frame)) {
                continue;
            }
            if (!oldest || entry.lastUsed < oldest.entry.lastUsed) {
                oldest = {key, entry};
            }
        }
        if (!oldest) {
            break;
        }
        dropThumbnail(oldest.key);
    }
}

/**
 * 当前显示中的帧不参与淘汰
 */
function pinnedFrames() {
    const pinned = new Set([cursor]);
    for (let frame = Math.max(0, cursor - STRIP_SIDE_OFFSET); frame <= Math.min(totalFrames - 1, cursor + STRIP_SIDE_OFFSET); frame++) {
        pinned.add(frame);
    }
    return pinned;
}

/**
 * 删除缓存条目并释放地址
 */
function dropThumbnail(key) {
    const entry = thumbnails.get(key);
    if (!entry) {
        return;
    }
    thumbnails.delete(key);
    thumbnailBytes -= entry.bytes;
    URL.revokeObjectURL(entry.url);
}

/**
 * 释放全部缩略图
 * 切换项目或离开页面时调用
 */
function releaseThumbnails() {
    thumbnailEpoch++;
    stopStripCycle();
    for (const [, entry] of thumbnails) {
        URL.revokeObjectURL(entry.url);
    }
    thumbnails.clear();
    thumbnailBytes = 0;
    thumbnailQueue.length = 0;
}

/**
 * 缩略图条的排布
 * 当前帧按原尺寸显示，两侧缩略图缩小后并排摆放；
 * 宽度不足时先缩小当前帧，仍不够则三张图一起等比缩小，保证都留在缩略图条内
 */
function stripLayout() {
    const style = getComputedStyle(strip);
    const usable = strip.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
    const baseWidth = stripItemWidth();
    const baseHeight = stripItemHeight();
    if (usable <= 0 || baseWidth <= 0) {
        return {currentWidth: 300, currentHeight: 169, sideWidth: 180, sideHeight: 101};
    }
    const gap = 12;
    const minSide = 140;
    const maxRatio = 0.75;
    // 当前帧优先保持原尺寸
    let currentWidth = Math.min(baseWidth, usable - minSide * 2 - gap * 2);
    if (currentWidth < minSide) {
        // 宽度实在不够时三张图一起等比缩小
        const total = baseWidth * (1 + 2 * maxRatio) + gap * 2;
        currentWidth = Math.floor(baseWidth * (usable / total));
    }
    currentWidth = Math.max(1, currentWidth);
    const currentHeight = Math.max(1, Math.floor(currentWidth * baseHeight / baseWidth));
    // 剩余空间分给两侧缩略图
    const room = usable - currentWidth - gap * 2;
    const sideWidth = Math.max(1, Math.min(Math.floor(currentWidth * maxRatio), Math.floor(room / 2)));
    const sideHeight = Math.max(1, Math.floor(currentHeight * sideWidth / currentWidth));
    return {currentWidth, currentHeight, sideWidth, sideHeight};
}

/**
 * 单张缩略图未缩放时的高度
 */
function stripItemHeight() {
    return parseFloat(getComputedStyle(document.documentElement)
        .getPropertyValue('--clip-strip-item-height')) || 240;
}

/**
 * 单张缩略图未缩放时的宽度
 * 缩略图按源视频比例缩放，因此用视频原始比例推算；
 * 视频信息尚未就绪时退回实测值
 */
function stripItemWidth() {
    const height = stripItemHeight();
    if (video.videoWidth > 0 && video.videoHeight > 0) {
        return height * (video.videoWidth / video.videoHeight);
    }
    const current = strip.querySelector('.clip-strip-item.current img');
    return current ? current.getBoundingClientRect().width : 0;
}

/**
 * 帧区间的时长（毫秒）
 * 区间为左闭右开 [start, end)，与导出时的区间口径一致；
 * end 超出末帧时按末帧的间隔补足
 */
function frameRangeMs(start, end) {
    if (!frameTimes.length || end <= start) {
        return 0;
    }
    const last = frameTimes.length - 1;
    const from = frameTimes[Math.min(start, last)];
    let to;
    if (end <= last) {
        to = frameTimes[end];
    } else {
        const tail = last > 0 ? frameTimes[last] - frameTimes[last - 1] : 0;
        to = frameTimes[last] + tail;
    }
    return Math.max(0, to - from);
}

/**
 * 渲染暂选状态
 */
function renderPending() {
    const info = document.getElementById('clip-pending-info');
    if (!pending || pending.end <= pending.start) {
        info.textContent = '暂选：无';
        info.className = '';
        renderTimeline();
        return;
    }
    const typeLabel = pending.type === 'KEEP' ? '保留' : '舍弃';
    const frames = pending.end - pending.start;
    const seconds = (frameRangeMs(pending.start, pending.end) / 1000).toFixed(3);
    info.textContent = `暂选：${typeLabel} ${pending.start} - ${pending.end}（${frames} 帧 / ${seconds} 秒）`;
    info.className = pending.type === 'KEEP' ? 'pending-keep' : 'pending-drop';
    renderTimeline();
}

function updateFrameInfo() {
    document.getElementById('clip-frame-info').textContent =
        totalFrames > 0 ? `帧 ${cursor} / ${totalFrames - 1}（共 ${totalFrames} 帧）` : '帧 -';
    document.getElementById('clip-video-time').textContent =
        totalFrames > 0 ? `时间 ${(frameTimeMs(cursor) / 1000).toFixed(3)} 秒` : '';
    updateCursorMarker();
}

function updateExportAvailability() {
    const button = document.getElementById('clip-export');
    const hasKeep = keepSegments().length > 0;
    button.disabled = !hasKeep;
    button.title = hasKeep ? '' : '没有保留任何片段，无法导出';
}

async function saveConfig() {
    const name = document.getElementById('clip-name').value.trim();
    const newLarge = parseInt(document.getElementById('clip-large-step').value, 10);
    const newSmall = parseInt(document.getElementById('clip-small-step').value, 10);
    if (!name) {
        showToast('项目名称不能为空', 'error');
        return;
    }
    if (!(newLarge > 0) || !(newSmall > 0)) {
        showToast('步长必须是正整数', 'error');
        return;
    }
    try {
        const updated = await api(`/api/clip-projects/${project.id}`, {
            method: 'PUT',
            body: JSON.stringify({name, largeStep: newLarge, smallStep: newSmall})
        });
        project = {...project, ...updated};
        largeStep = updated.largeStep;
        smallStep = updated.smallStep;
        document.getElementById('clip-title').textContent = `剪辑：${updated.name}`;
        showToast('配置已保存', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
}

/**
 * 导出
 */
async function startExport() {
    if (!keepSegments().length) {
        showToast('没有保留任何片段，无法导出', 'warning');
        return;
    }
    const button = document.getElementById('clip-export');
    button.disabled = true;
    try {
        const job = await api(`/api/clip-projects/${project.id}/render-jobs`, {method: 'POST'});
        setRenderStatus('QUEUED', '导出排队中');
        document.getElementById('clip-cancel-export').classList.remove('hidden');
        pollRenderJob(job.id);
    } catch (e) {
        button.disabled = false;
        showToast(e.message, 'error');
    }
}

/**
 * 显示导出文件的完整路径
 */
function showOutputPath(path) {
    const el = document.getElementById('clip-output-path');
    if (!path) {
        el.classList.add('hidden');
        el.textContent = '';
        return;
    }
    el.textContent = path;
    el.title = path;
    el.classList.remove('hidden');
}

function pollRenderJob(jobId) {    clearInterval(renderPollTimer);
    renderPollTimer = setInterval(async () => {
        try {
            const job = await api(`/api/clip-render-jobs/${jobId}`);
            setRenderStatus(job.status, `导出中 ${job.progressPercent}%`);
            if (job.status === 'SUCCEEDED') {
                clearInterval(renderPollTimer);
                setRenderStatus('SUCCEEDED', '导出完成');
                const link = document.getElementById('clip-download');
                link.href = `/api/clip-render-jobs/${jobId}/download`;
                link.classList.remove('hidden');
                document.getElementById('clip-cancel-export').classList.add('hidden');
                document.getElementById('clip-export').disabled = false;
                // 导出完成后显示输出文件的完整路径，方便直接找到文件
                showOutputPath(job.outputPath);
                showToast('导出完成，已登记到视频库', 'success');
            } else if (job.status === 'FAILED') {
                clearInterval(renderPollTimer);
                setRenderStatus('FAILED', '导出失败');
                document.getElementById('clip-cancel-export').classList.add('hidden');
                document.getElementById('clip-export').disabled = false;
                showOutputPath(null);
                showToast(job.failureMessage || '导出失败', 'error');
            } else if (job.status === 'CANCELLED') {
                clearInterval(renderPollTimer);
                setRenderStatus('CANCELLED', '导出已取消');
                document.getElementById('clip-cancel-export').classList.add('hidden');
                document.getElementById('clip-export').disabled = false;
                showOutputPath(null);
            }
        } catch (e) {
            clearInterval(renderPollTimer);
            document.getElementById('clip-export').disabled = false;
            setRenderStatus('FAILED', '导出失败');
            showToast(e.message, 'error');
        }
    }, 800);
}

async function cancelExport() {
    try {
        const active = await api('/api/clip-render-jobs/active');
        if (!active) {
            return;
        }
        await api(`/api/clip-render-jobs/${active.id}/cancel`, {method: 'POST'});
        clearInterval(renderPollTimer);
        setRenderStatus('CANCELLED', '导出已取消');
        document.getElementById('clip-cancel-export').classList.add('hidden');
        document.getElementById('clip-export').disabled = false;
    } catch (e) {
        showToast(e.message, 'error');
    }
}

function setRenderStatus(status, text) {
    const element = document.getElementById('clip-render-status');
    element.textContent = text;
    element.className = `render-status ${status.toLowerCase()}`;
}

function showToast(message, type = 'success') {
    const host = document.getElementById('toast-host');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    host.appendChild(toast);
    setTimeout(() => toast.remove(), 3000);
}
