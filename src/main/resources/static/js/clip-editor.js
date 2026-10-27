import {api, escapeHtml} from './api.js';

/**
 * 剪辑编辑器
 * 从上到下依次为：视频预览、重叠缩略图、保留帧区域、时间轴、舍弃帧区域
 *
 * 交互约定：
 * - ↑/↓ 设定当前暂选类型（保留/舍弃）并向后取大步长
 * - → 追加小步长
 * - ← 从暂选末尾减少小步长
 * - Enter 存档暂选，类型不随之切换
 * 暂选只存在于前端内存，按 Enter 存档后才持久化
 */

const STRIP_RADIUS = 4;
const STRIP_FETCH_LIMIT = 60;
/** 相邻缩略图的最大水平间距（像素），实际间距按缩略图条宽度自适应 */
const STRIP_MAX_OFFSET = 96;
const STRIP_MIN_OFFSET = 20;

let project = null;
let segments = [];
let totalFrames = 0;
let largeStep = 10;
let smallStep = 5;

/** 当前游标所在帧 */
let cursor = 0;
/** 暂选区间 {start, end, type}，未存档 */
let pending = null;
/** 缩略图缓存 frameNo -> dataURL */
const thumbnails = new Map();
/** 帧号 -> 源视频时间（毫秒） */
let frameTimes = new Int32Array(0);
/** 导出任务轮询句柄 */
let renderPollTimer = null;
/** 跳转完成后需要恢复的帧号，非空时暂停游标跟随 */
let seekRestoreFrame = null;

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
    document.getElementById('clip-save-config').addEventListener('click', saveConfig);
    document.getElementById('clip-export').addEventListener('click', startExport);
    document.getElementById('clip-cancel-export').addEventListener('click', cancelExport);
    video.addEventListener('timeupdate', onTimeUpdate);
    video.addEventListener('seeked', onSeeked);
    window.addEventListener('resize', () => renderTimeline());
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
            await refreshStrip();
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
 */
function stepSelection(type) {
    if (totalFrames <= 0) {
        return;
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
    cursor = target;
    seekRestoreFrame = cursor;
    video.currentTime = frameTimeMs(cursor) / 1000;
    updateFrameInfo();
    refreshStrip();
}

/**
 * Enter：存档暂选，类型不随之切换
 */
async function commitPending() {
    if (!pending || pending.end <= pending.start) {
        showToast('没有可存档的暂选', 'warning');
        return;
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
        showToast('片段已存档', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
}

function togglePlay() {
    if (video.paused) {
        video.play();
    } else {
        video.pause();
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
    cursor = Math.min(totalFrames - 1, Math.floor(ratio * totalFrames));
    // 跳到新位置后暂选从当前位置重新开始
    pending = null;
    // 跳转期间暂停游标跟随，避免浏览器实际落点回写覆盖用户选中的帧
    seekRestoreFrame = cursor;
    video.currentTime = frameTimeMs(cursor) / 1000;
    renderPending();
    updateFrameInfo();
    refreshStrip();
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

    const kept = keepSegments().reduce((sum, segment) => sum + (segment.endFrame - segment.startFrame), 0);
    document.getElementById('clip-keep-summary').textContent =
        totalFrames > 0 ? `保留 ${kept} / ${totalFrames} 帧` : '';
}

/**
 * 时间轴：同时显示保留、舍弃与未标记三种状态
 */
function renderTimeline() {
    if (!totalFrames) {
        timeline.innerHTML = '';
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
}

function renderSegmentList() {
    const list = document.getElementById('clip-segment-list');
    if (!segments.length) {
        list.innerHTML = '<div class="empty-tip">暂无已存档片段</div>';
        return;
    }
    list.innerHTML = segments.map(segment => `
    <div class="clip-segment-item ${segment.type === 'KEEP' ? 'keep' : 'drop'}">
      <span class="clip-segment-type">${segment.type === 'KEEP' ? '保留' : '舍弃'}</span>
      <span class="clip-segment-range">${segment.startFrame} - ${segment.endFrame}</span>
      <span class="clip-segment-count">${segment.endFrame - segment.startFrame} 帧</span>
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
 * 渲染重叠缩略图
 * 当前帧最大，前后帧依次变小并相互重叠
 */
async function refreshStrip() {
    if (!totalFrames) {
        strip.innerHTML = '';
        return;
    }
    const start = Math.max(0, cursor - STRIP_RADIUS);
    const end = Math.min(totalFrames - 1, cursor + STRIP_RADIUS);
    const frames = [];
    for (let frame = start; frame <= end; frame++) {
        frames.push(frame);
    }
    const wanted = frames.filter(frame => !thumbnails.has(frame)).slice(0, STRIP_FETCH_LIMIT);

    // 以当前帧为中心排布，当前帧位于正中并压在相邻帧之上
    const center = cursor - start;
    const offset = stripOffset(frames, center);
    strip.innerHTML = frames.map((frame, index) => {
        const cached = thumbnails.get(frame);
        const distance = Math.abs(frame - cursor);
        const scale = Math.max(0.5, 1 - distance * 0.1);
        const shift = (index - center) * offset;
        const zIndex = 20 - distance;
        const image = cached
            ? `<img src="${cached}" alt="第${frame}帧">`
            : `<div class="clip-strip-placeholder"></div>`;
        return `<div class="clip-strip-item ${frame === cursor ? 'current' : ''}"
                     data-frame="${frame}"
                     style="left:calc(50% + ${shift}px);transform:translateX(-50%) scale(${scale});z-index:${zIndex}">${image}</div>`;
    }).join('');

    for (const frame of wanted) {
        await loadThumbnail(frame);
    }
    // 缩略图就位后重绘一次
    if (wanted.length) {
        refreshStrip();
    }
}

/**
 * 计算相邻缩略图的水平间距
 * 缩略图放大后单张宽度较大，间距按缩略图条的实际宽度收紧，
 * 保证最外侧的缩略图不会被裁掉
 */
function stripOffset(frames, center) {
    const stripWidth = strip.getBoundingClientRect().width;
    const itemWidth = stripItemWidth();
    if (stripWidth <= 0 || itemWidth <= 0) {
        return STRIP_MAX_OFFSET;
    }
    // 当前帧两侧最远的缩略图到中心的距离（两侧可能不对称）
    const reach = Math.max(...frames.map((frame, index) => Math.abs(index - center)));
    if (reach === 0) {
        return STRIP_MAX_OFFSET;
    }
    // 最外侧缩略图缩放后约占位宽的六成，其半宽决定中心到边缘的可用空间
    const scale = Math.max(0.5, 1 - reach * 0.1);
    const edgeHalfWidth = itemWidth * scale / 2;
    const available = stripWidth / 2 - edgeHalfWidth;
    return Math.max(STRIP_MIN_OFFSET, Math.min(STRIP_MAX_OFFSET, available / reach));
}

/**
 * 单张缩略图未缩放时的宽度
 * 缩略图按源视频比例缩放，因此用视频原始比例推算；
 * 视频信息尚未就绪时退回实测值
 */
function stripItemWidth() {
    const cssHeight = parseFloat(getComputedStyle(document.documentElement)
        .getPropertyValue('--clip-strip-item-height')) || 240;
    if (video.videoWidth > 0 && video.videoHeight > 0) {
        return cssHeight * (video.videoWidth / video.videoHeight);
    }
    const current = strip.querySelector('.clip-strip-item.current img');
    return current ? current.getBoundingClientRect().width : 0;
}

async function loadThumbnail(frame) {
    try {
        const response = await fetch(`/api/clip-projects/${project.id}/frames/${frame}/thumbnail`);
        if (!response.ok) {
            return;
        }
        const blob = await response.blob();
        const reader = new FileReader();
        reader.onload = () => {
            thumbnails.set(frame, reader.result);
        };
        reader.readAsDataURL(blob);
    } catch (e) {
        // 缩略图缺失不影响主要流程
    }
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

function pollRenderJob(jobId) {
    clearInterval(renderPollTimer);
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
                showToast('导出完成，已登记到视频库', 'success');
            } else if (job.status === 'FAILED') {
                clearInterval(renderPollTimer);
                setRenderStatus('FAILED', '导出失败');
                document.getElementById('clip-cancel-export').classList.add('hidden');
                document.getElementById('clip-export').disabled = false;
                showToast(job.failureMessage || '导出失败', 'error');
            } else if (job.status === 'CANCELLED') {
                clearInterval(renderPollTimer);
                setRenderStatus('CANCELLED', '导出已取消');
                document.getElementById('clip-cancel-export').classList.add('hidden');
                document.getElementById('clip-export').disabled = false;
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
