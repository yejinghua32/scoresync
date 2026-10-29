import {api, escapeHtml} from './api.js';

/**
 * 剪辑编辑器
 * 左侧是视频预览、保留帧时间轴与源时间轴，右侧是已存档片段列表
 *
 * 交互约定：
 * - ↑/↓ 设定当前暂选类型（保留/舍弃）并取大步长，切换类型时只改类型
 * - → 追加小步长
 * - ← 从暂选末尾减少小步长
 * - Delete 清除暂选并回到上一个片段的最后一帧
 * - Enter 存档暂选，类型不随之切换
 * 暂选只存在于前端内存，按 Enter 才持久化
 * 视频下方的保留帧时间轴按成片时间定位，播放时只走保留片段
 * 左键单击已存档片段定位到它的第一帧，右键定位到最后一帧，双击循环播放该片段；
 * 暂选未存档时加减帧会播放当前帧之前的 1 秒，只播一次
 */

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
/** 帧号 -> 源视频时间（毫秒） */
let frameTimes = new Int32Array(0);
/** 导出任务轮询句柄 */
let renderPollTimer = null;
/** 跳转完成后需要恢复的帧号，非空时暂停游标跟随 */
let seekRestoreFrame = null;
/** 用户是否手动定位过视频，手动定位期间跳播预览让位 */
let manualPosition = false;
/** 循环预览的源视频区间 {startMs, endMs}，非空时只在这一段内循环播放 */
let loopRange = null;
/** 循环边界的检查定时器 */
let loopTimer = null;

const video = document.getElementById('clip-video');
const timeline = document.getElementById('clip-timeline');
const cursorMarker = document.getElementById('clip-timeline-cursor');
const keepTimeline = document.getElementById('clip-keep-timeline');
const keepCursorMarker = document.getElementById('clip-keep-timeline-cursor');

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
    keepTimeline.addEventListener('click', onKeepTimelineClick);
    document.getElementById('clip-segment-list').addEventListener('click', onSegmentClick);
    document.getElementById('clip-segment-list').addEventListener('dblclick', onSegmentDoubleClick);
    document.getElementById('clip-segment-list').addEventListener('contextmenu', onSegmentContextMenu);
    document.getElementById('clip-save-config').addEventListener('click', saveConfig);
    document.getElementById('clip-export').addEventListener('click', startExport);
    document.getElementById('clip-cancel-export').addEventListener('click', cancelExport);
    video.addEventListener('timeupdate', onTimeUpdate);
    video.addEventListener('seeked', onSeeked);
    // 暂停后结束循环预览
    video.addEventListener('pause', onPlaybackPaused);
    // 用户把播放位置拖出循环区间时结束循环预览
    video.addEventListener('seeking', onVideoSeeking);
    window.addEventListener('resize', () => {
        renderTimeline();
    });
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
        case 'Delete':
            e.preventDefault();
            clearPending();
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
 * ↑/↓：设定暂选类型
 * 已有暂选时切换类型只改类型，区间与游标保持不变，也不会自动存档；
 * 同类型连按向后取大步长；没有暂选时从当前帧开始新建一段暂选
 */
function stepSelection(type) {
    if (totalFrames <= 0) {
        return;
    }
    clearLoop();
    const step = Math.max(1, largeStep);
    if (!pending) {
        cursor = Math.min(totalFrames - 1, Math.max(0, cursor));
        pending = {start: cursor, end: Math.min(totalFrames, cursor + step), type};
        // 游标推进到下一帧，末尾按总帧数截断
        cursor = pending.end;
    } else if (pending.type !== type) {
        // 切换类型：区间保持原样，只改类型
        pending.type = type;
    } else {
        // 同一类型继续向后取大步长
        pending.end = Math.min(totalFrames, pending.end + step);
        cursor = pending.end;
    }
    renderPending();
    updateFrameInfo();
    seekVideoToFrame(cursor);
}

/**
 * Delete：清除暂选，并把当前帧定位到上一个片段的最后一帧
 * 没有暂选时不做任何操作
 */
function clearPending() {
    if (!pending) {
        return;
    }
    const anchor = pending.start;
    pending = null;
    renderPending();
    // 找到暂选开始之前最近的一个已存档片段，定位到它的最后一帧
    const previous = segments
        .filter(segment => segment.endFrame <= anchor)
        .sort((a, b) => b.endFrame - a.endFrame)[0];
    const frame = previous
        ? Math.max(previous.startFrame, previous.endFrame - 1)
        : Math.max(0, Math.min(totalFrames - 1, anchor - 1));
    moveCursorTo(frame);
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
    // 暂选尚未存档时，播放暂选最后一帧之前的 1 秒
    playLastSecond(pending.end - 1);
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
    // 暂选尚未存档时，播放暂选最后一帧之前的 1 秒
    if (pending) {
        playLastSecond(pending.end - 1);
    }
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
    clearLoop();
    const target = Math.min(totalFrames - 1, Math.max(0, frame));
    cursor = target;
    seekVideoToFrame(target);
    updateFrameInfo();
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
    clearLoop();
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
}

/**
 * 暂停：结束循环预览
 */
function onPlaybackPaused() {
    clearLoop();
}

/**
 * 用户把播放位置拖出循环区间时结束循环预览
 * 循环自身的起终点跳转都落在区间内，不会被误判
 */
function onVideoSeeking() {
    if (!loopRange) {
        return;
    }
    const currentMs = video.currentTime * 1000;
    if (currentMs < loopRange.startMs - 250 || currentMs > loopRange.endMs + 250) {
        clearLoop();
    }
}

/**
 * 播放源视频的一段区间
 * 已存档片段预览按区间循环，暂选的 1 秒预览只播一次
 */
function playLoop(startFrame, endFrame, repeat = true) {
    if (!totalFrames || endFrame <= startFrame) {
        return;
    }
    const start = Math.max(0, Math.min(totalFrames - 1, startFrame));
    const end = Math.min(totalFrames, Math.max(start + 1, endFrame));
    loopRange = {
        startMs: frameTimeMs(start),
        endMs: frameTimeMs(start) + frameRangeMs(start, end),
        repeat
    };
    cursor = start;
    seekVideoToFrame(start);
    manualPosition = false;
    updateFrameInfo();
    // 浏览器拒绝播放时不再保留循环区间，避免之后点播放被限制在这一段
    video.play().catch(() => clearLoop());
    startLoopTimer();
}

/**
 * 播放当前帧之前的 1 秒，用于确认暂选边界
 * 只播一次，播完即停
 */
function playLastSecond(frame) {
    if (!totalFrames) {
        return;
    }
    const anchor = Math.max(0, Math.min(totalFrames - 1, frame));
    const endFrame = Math.min(totalFrames, anchor + 1);
    const endMs = frameTimeMs(anchor) + frameRangeMs(anchor, endFrame);
    playLoop(nearestFrame(Math.max(0, endMs - 1000)), endFrame, false);
}

function startLoopTimer() {
    stopLoopTimer();
    loopTimer = setInterval(checkLoopBoundary, 50);
}

function stopLoopTimer() {
    if (loopTimer) {
        clearInterval(loopTimer);
        loopTimer = null;
    }
}

/**
 * 结束循环预览
 */
function clearLoop() {
    loopRange = null;
    stopLoopTimer();
}

/**
 * 播到区间末尾时的处理
 * 循环区间跳回起点，一次性区间播完即停
 */
function checkLoopBoundary() {
    if (!loopRange || video.paused) {
        return;
    }
    if (video.currentTime * 1000 >= loopRange.endMs) {
        if (loopRange.repeat) {
            video.currentTime = loopRange.startMs / 1000;
        } else {
            video.pause();
        }
    }
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
 * 左键单击已存档片段：定位到该片段的第一帧
 * 片段是半开区间 [startFrame, endFrame)
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
    moveCursorTo(start);
    renderPending();
}

/**
 * 右键单击已存档片段：定位到该片段的最后一帧
 */
function onSegmentContextMenu(e) {
    const item = e.target.closest('.clip-segment-item');
    if (!item) {
        return;
    }
    e.preventDefault();
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
 * 双击已存档片段：循环播放该片段
 */
function onSegmentDoubleClick(e) {
    if (e.target.closest('.clip-segment-delete')) {
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
    renderPending();
    playLoop(start, end);
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
    clearLoop();
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
    if (loopRange) {
        // 正在循环预览某一段，跳播预览让位
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
        renderKeepTimeline();
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
    renderKeepTimeline();
}

/**
 * 保留帧总时长（毫秒）
 */
function keptTotalMs(keeps = keepSegments()) {
    return keeps.reduce((sum, segment) => sum + frameRangeMs(segment.startFrame, segment.endFrame), 0);
}

/**
 * 渲染保留帧时间轴
 * 已存档的保留片段按自身时长首尾相接，整条轴的长度就是成片时长；
 * 点击时按成片时间换算回源视频的帧
 */
function renderKeepTimeline() {
    if (!keepTimeline) {
        return;
    }
    const keeps = keepSegments();
    const totalMs = keptTotalMs(keeps);
    const duration = document.getElementById('clip-keep-duration');
    if (duration) {
        duration.textContent = totalMs > 0 ? `保留时长 ${formatDuration(totalMs)}` : '';
    }
    if (!keeps.length || totalMs <= 0) {
        keepTimeline.innerHTML = '<div class="clip-keep-empty">暂无保留片段</div>';
        updateKeepCursor(0, keeps);
        renderKeepRuler(0);
        return;
    }
    let offset = 0;
    const bars = keeps.map(segment => {
        const durationMs = frameRangeMs(segment.startFrame, segment.endFrame);
        const left = (offset / totalMs) * 100;
        const width = (durationMs / totalMs) * 100;
        offset += durationMs;
        return `<div class="clip-timeline-bar keep"
                     style="left:${left}%;width:${width}%"
                     title="保留 ${segment.startFrame}-${segment.endFrame}"></div>`;
    }).join('');
    keepTimeline.innerHTML = bars;
    updateKeepCursor(totalMs, keeps);
    renderKeepRuler(totalMs);
}

/**
 * 当前帧在保留帧时间轴上的成片时间，不在任何保留片段内时返回 null
 */
function keepOffsetForFrame(frame, keeps) {
    let offset = 0;
    for (const segment of keeps) {
        if (frame >= segment.startFrame && frame < segment.endFrame) {
            return offset + (frameTimeMs(frame) - frameTimeMs(segment.startFrame));
        }
        offset += frameRangeMs(segment.startFrame, segment.endFrame);
    }
    return null;
}

/**
 * 保留帧时间轴上的游标
 */
function updateKeepCursor(totalMs, keeps = keepSegments()) {
    if (!keepCursorMarker) {
        return;
    }
    const offset = totalFrames > 0 ? keepOffsetForFrame(cursor, keeps) : null;
    if (offset === null || totalMs <= 0) {
        keepCursorMarker.classList.add('hidden');
        return;
    }
    keepCursorMarker.classList.remove('hidden');
    keepCursorMarker.style.left = `${Math.min(offset / totalMs, 1) * 100}%`;
}

/**
 * 点击保留帧时间轴
 * 按成片时间定位到对应的源帧，并只播放保留片段
 */
function onKeepTimelineClick(e) {
    if (!totalFrames) {
        return;
    }
    const keeps = keepSegments();
    const totalMs = keptTotalMs(keeps);
    if (!keeps.length || totalMs <= 0) {
        return;
    }
    const rect = keepTimeline.getBoundingClientRect();
    if (rect.width <= 0) {
        return;
    }
    const ratio = Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 0.9999);
    let remaining = ratio * totalMs;
    let target = keeps[keeps.length - 1];
    for (const segment of keeps) {
        const durationMs = frameRangeMs(segment.startFrame, segment.endFrame);
        if (remaining < durationMs) {
            target = segment;
            break;
        }
        remaining -= durationMs;
    }
    const sourceMs = frameTimeMs(target.startFrame) + Math.max(0, remaining);
    const frame = Math.min(target.endFrame - 1,
        Math.max(target.startFrame, nearestFrame(sourceMs)));
    clearLoop();
    pending = null;
    renderPending();
    cursor = frame;
    seekVideoToFrame(frame);
    // 播放只走保留片段：让跳播预览接管这次手动定位
    manualPosition = false;
    updateFrameInfo();
    video.play().catch(() => {});
}

/**
 * 时间轴刻度
 * 刻度间隔按可用宽度挑选，放得下时才显示加粗的时间标签
 */
function renderTimelineRuler() {
    const totalMs = totalFrames > 0 ? frameRangeMs(0, totalFrames) : 0;
    renderRuler(document.getElementById('clip-timeline-ruler'), totalMs);
}

/**
 * 保留帧时间轴刻度，标签是成片时间
 */
function renderKeepRuler(totalMs) {
    renderRuler(document.getElementById('clip-keep-timeline-ruler'), totalMs);
}

function renderRuler(ruler, totalMs) {
    if (!ruler) {
        return;
    }
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
         title="左键定位到首帧，右键定位到末帧，双击循环播放">
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
    updateKeepCursor(keptTotalMs());
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
