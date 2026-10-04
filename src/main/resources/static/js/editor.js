import {api} from './api.js';

const projectId = Number(new URLSearchParams(location.search).get('projectId'));
if (!Number.isInteger(projectId) || projectId < 1) {
    location.replace('/');
}

const video = document.getElementById('match-video');
const eventList = document.getElementById('event-list');
const saveStatusEl = document.getElementById('save-status');
const eventModal = document.getElementById('event-modal');
const deleteModal = document.getElementById('delete-event-modal');
const previewImg = document.getElementById('scoreboard-preview');
const templateSelect = document.getElementById('scoreboard-template');
const startRenderBtn = document.getElementById('start-render');
const cancelRenderBtn = document.getElementById('cancel-render');
const downloadLink = document.getElementById('download-render');
const renderStatusEl = document.getElementById('render-status');

let currentState = null;
let pendingEventId = null;
let writeQueue = Promise.resolve();
let activeJobId = null;
let pollTimer = null;
let lastPreviewKey = null;

function setSaveStatus(text, type = 'success') {
    saveStatusEl.textContent = text;
    saveStatusEl.className = type;
    setTimeout(() => {
        if (saveStatusEl.textContent === text) {
            saveStatusEl.textContent = '';
        }
    }, 3000);
}

function showError(message) {
    const toast = document.createElement('div');
    toast.className = 'toast error';
    toast.textContent = message;
    document.getElementById('toast-host').appendChild(toast);
    setTimeout(() => toast.remove(), 5000);
}

async function loadState() {
    try {
        const state = await api(`/api/projects/${projectId}/editor-state`);
        currentState = state;
        video.src = `/api/media/${state.video.id}`;
        if (state.project && state.project.scoreboardTemplate) {
            templateSelect.value = state.project.scoreboardTemplate;
        }
        if (state.project && state.project.firstServer) {
            const radio = document.querySelector(`input[name="first-server"][value="${state.project.firstServer}"]`);
            if (radio) radio.checked = true;
            lastPreviewKey = null;
        }
        renderState(state);
        setSaveStatus('已自动保存');
        loadActiveJob();
    } catch (error) {
        showError('保存失败，已保留最后一次服务端状态');
    }
}

function teamLabel(side, name) {
    const trimmed = (name || '').trim();
    return trimmed ? `${side}队-${trimmed}` : `${side}队`;
}

function applyTeamNames(project) {
    if (!project) return;
    const labelA = teamLabel('A', project.playerA);
    const labelB = teamLabel('B', project.playerB);
    document.getElementById('team-label-a').textContent = labelA;
    document.getElementById('team-label-b').textContent = labelB;
    document.getElementById('score-a').textContent = `${labelA}得分`;
    document.getElementById('score-b').textContent = `${labelB}得分`;
}

function renderState(state) {
    currentState = state;
    applyTeamNames(state.project);
    document.getElementById('current-set').textContent = `当前局数: ${state.match.currentSetNo}  |  ${state.match.currentScoreA} - ${state.match.currentScoreB}`;
    document.getElementById('set-wins').textContent = `A胜: ${state.match.setWinsA} 局  |  B胜: ${state.match.setWinsB} 局`;
    document.getElementById('completed-sets').textContent = `已完成局数: ${(state.match.completedSets || []).length}`;

    renderEventList(state.events);
    refreshScoreboardPreview();
    scrollToActiveEvent();
}

function activeCueAt(timeMs) {
    if (!currentState || !currentState.scoreboardCues) return null;
    return currentState.scoreboardCues
        .filter(cue => cue.startTimeMs <= timeMs)
        .at(-1);
}

function refreshScoreboardPreview() {
    if (!currentState) return;
    const videoTimeMs = Math.round(video.currentTime * 1000);
    const cue = activeCueAt(videoTimeMs);
    const template = templateSelect.value;
    const firstServer = document.querySelector('input[name="first-server"]:checked')?.value || 'A';
    const timeMs = videoTimeMs;
    const sequenceNo = cue ? cue.sequenceNo : 0;
    const previewKey = `${template}|${firstServer}|${timeMs}|${sequenceNo}`;
    if (previewKey === lastPreviewKey) return;
    lastPreviewKey = previewKey;

    const renderWidth = 960;
    const url = `/api/projects/${projectId}/scoreboard-preview?template=${template}&timeMs=${timeMs}&renderWidth=${renderWidth}`;
    previewImg.classList.remove('loaded');
    // 比分牌 PNG 宽度随可见局数变化，按固有宽度换算为视频帧的相对宽度，
    // 保证浏览器叠加预览与导出视频中的比分牌占屏比例一致。
    previewImg.onload = () => {
        const naturalWidth = previewImg.naturalWidth;
        if (Number.isInteger(naturalWidth) && naturalWidth > 0 && renderWidth > 0) {
            previewImg.style.width = `${naturalWidth / renderWidth * 100}%`;
        }
        previewImg.classList.add('loaded');
    };
    previewImg.src = url;
}

function renderEventList(events) {
    eventList.innerHTML = '';
    events.forEach((event, index) => {
        const item = document.createElement('div');
        item.className = 'event-item';
        item.dataset.index = index;
        item.innerHTML = `
      <span class="event-time">${(event.videoTimeMs / 1000).toFixed(2)}s</span>
      <span class="event-player">${event.playerSide}</span>
      <span class="event-set">第${event.setNo}局</span>
      <span class="event-score">${event.scoreA}-${event.scoreB}</span>
      <button class="btn-position" data-time-ms="${event.videoTimeMs}">定位</button>
      <button class="btn-edit" data-id="${event.eventId}">编辑</button>
      <button class="btn-delete" data-id="${event.eventId}">删除</button>
    `;
        item.querySelector('.btn-position').addEventListener('click', () => {
            video.currentTime = event.videoTimeMs / 1000;
            video.pause();
        });
        item.querySelector('.btn-edit').addEventListener('click', () => openEditModal(event));
        item.querySelector('.btn-delete').addEventListener('click', () => openDeleteModal(event.eventId));
        eventList.appendChild(item);
    });
}

function scrollToActiveEvent() {
    if (!currentState || !currentState.events.length) return;
    const currentTimeMs = video.currentTime * 1000;
    const events = currentState.events;
    let activeIndex = -1;
    for (let i = events.length - 1; i >= 0; i--) {
        if (events[i].videoTimeMs <= currentTimeMs) {
            activeIndex = i;
            break;
        }
    }
    document.querySelectorAll('.event-item').forEach((item, i) => {
        item.classList.toggle('active', i === activeIndex);
    });
    if (activeIndex >= 0) {
        const activeEl = document.querySelector(`.event-item[data-index="${activeIndex}"]`);
        if (activeEl) {
            activeEl.scrollIntoView({behavior: 'smooth', block: 'nearest'});
        }
    }
}

function openEditModal(event) {
    pendingEventId = event.eventId;
    document.getElementById('event-id').value = event.eventId;
    document.getElementById('event-time-ms').value = event.videoTimeMs;
    document.getElementById('event-player-side').value = event.playerSide;
    eventModal.classList.remove('hidden');
}

function openDeleteModal(eventId) {
    pendingEventId = eventId;
    deleteModal.classList.remove('hidden');
}

function closeEditModal() {
    eventModal.classList.add('hidden');
    pendingEventId = null;
}

function closeDeleteModal() {
    deleteModal.classList.add('hidden');
    pendingEventId = null;
}

document.getElementById('event-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const eventId = pendingEventId;
    const videoTimeMs = Number(document.getElementById('event-time-ms').value);
    const playerSide = document.getElementById('event-player-side').value;

    closeEditModal();

    try {
        const state = await api(`/api/events/${eventId}`, {
            method: 'PATCH',
            body: JSON.stringify({videoTimeMs, playerSide})
        });
        renderState(state);
        scrollToActiveEvent();
        setSaveStatus('已自动保存');
    } catch (error) {
        showError(error.message);
        loadState();
    }
});

document.getElementById('modal-cancel').addEventListener('click', closeEditModal);
document.getElementById('delete-cancel').addEventListener('click', closeDeleteModal);
document.getElementById('confirm-delete').addEventListener('click', async () => {
    const eventId = pendingEventId;
    closeDeleteModal();

    try {
        const state = await api(`/api/events/${eventId}`, {method: 'DELETE'});
        renderState(state);
        scrollToActiveEvent();
        setSaveStatus('已自动保存');
    } catch (error) {
        showError(error.message);
        loadState();
    }
});

document.getElementById('score-a').addEventListener('click', () => addPoint('A'));
document.getElementById('score-b').addEventListener('click', () => addPoint('B'));
document.getElementById('undo-score').addEventListener('click', () => enqueueWrite(() => undoLastPoint()));

async function addPoint(playerSide) {
    const videoTimeMs = Math.round(video.currentTime * 1000);
    const state = await api(`/api/projects/${projectId}/events`, {
        method: 'POST',
        body: JSON.stringify({videoTimeMs, playerSide})
    });
    renderState(state);
    setSaveStatus('已自动保存');
}

async function undoLastPoint() {
    const state = await api(`/api/projects/${projectId}/events/last`, {method: 'DELETE'});
    renderState(state);
    setSaveStatus('已自动保存');
}

function enqueueWrite(action) {
    writeQueue = writeQueue.then(action).catch(error => {
        showError(error.message);
    });
    return writeQueue;
}

function isEditableTarget(target) {
    return target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement ||
        target instanceof HTMLSelectElement || target.isContentEditable;
}

document.addEventListener('keydown', (event) => {
    if (isEditableTarget(event.target)) return;

    const seekSeconds = Number(video.dataset.seekSeconds) || 5;

    if (event.code === 'KeyA') {
        event.preventDefault();
        enqueueWrite(() => addPoint('A'));
    } else if (event.code === 'KeyD' || event.code === 'KeyB') {
        event.preventDefault();
        enqueueWrite(() => addPoint('B'));
    } else if (event.code === 'KeyZ') {
        event.preventDefault();
        enqueueWrite(() => undoLastPoint());
    } else if (event.code === 'Space') {
        event.preventDefault();
        if (video.paused) {
            video.play();
        } else {
            video.pause();
        }
    } else if (event.code === 'ArrowLeft') {
        event.preventDefault();
        video.currentTime = Math.max(0, video.currentTime - seekSeconds);
    } else if (event.code === 'ArrowRight') {
        event.preventDefault();
        video.currentTime = Math.min(video.duration, video.currentTime + seekSeconds);
    }
});

video.addEventListener('timeupdate', () => {
    const seconds = video.currentTime.toFixed(2);
    document.getElementById('video-time').textContent = `时间: ${seconds}s`;
    refreshScoreboardPreview();
    scrollToActiveEvent();
});

templateSelect.addEventListener('change', async () => {
    lastPreviewKey = null;
    try {
        await api(`/api/projects/${projectId}/scoreboard-template`, {
            method: 'PATCH',
            body: JSON.stringify({templateCode: templateSelect.value})
        });
        setSaveStatus('模板已保存');
    } catch (error) {
        showError(error.message);
    }
    refreshScoreboardPreview();
});

document.querySelectorAll('input[name="first-server"]').forEach((radio) => {
    radio.addEventListener('change', async (e) => {
        lastPreviewKey = null;
        try {
            await api(`/api/projects/${projectId}/first-server`, {
                method: 'PATCH',
                body: JSON.stringify({firstServer: e.target.value})
            });
            setSaveStatus('先发球已保存');
        } catch (error) {
            showError(error.message);
        }
        refreshScoreboardPreview();
    });
});

async function loadActiveJob() {
    try {
        const job = await api('/api/render-jobs/active');
        if (job) {
            activeJobId = job.id;
            updateRenderCard(job);
            startPolling();
        } else {
            updateRenderCard(null);
        }
    } catch (error) {
        updateRenderCard(null);
    }
}

function updateRenderCard(job) {
    if (!job) {
        renderStatusEl.textContent = '未开始';
        renderStatusEl.className = 'render-status';
        startRenderBtn.disabled = false;
        cancelRenderBtn.classList.add('hidden');
        downloadLink.classList.add('hidden');
        return;
    }

    startRenderBtn.disabled = true;

    if (job.status === 'QUEUED') {
        renderStatusEl.textContent = '排队中…';
        renderStatusEl.className = 'render-status progress';
        cancelRenderBtn.classList.remove('hidden');
        downloadLink.classList.add('hidden');
    } else if (job.status === 'RUNNING') {
        renderStatusEl.textContent = `导出中 ${job.progressPercent}%`;
        renderStatusEl.className = 'render-status progress';
        cancelRenderBtn.classList.remove('hidden');
        downloadLink.classList.add('hidden');
    } else if (job.status === 'SUCCEEDED') {
        renderStatusEl.textContent = '已完成';
        renderStatusEl.className = 'render-status success';
        cancelRenderBtn.classList.add('hidden');
        downloadLink.classList.remove('hidden');
        downloadLink.href = `/api/render-jobs/${job.id}/download`;
    } else if (job.status === 'FAILED') {
        renderStatusEl.textContent = `失败：${job.failureMessage || '未知错误'}`;
        renderStatusEl.className = 'render-status error';
        cancelRenderBtn.classList.add('hidden');
        downloadLink.classList.add('hidden');
        startRenderBtn.disabled = false;
    } else if (job.status === 'CANCELLED') {
        renderStatusEl.textContent = '已取消';
        renderStatusEl.className = 'render-status';
        cancelRenderBtn.classList.add('hidden');
        downloadLink.classList.add('hidden');
        startRenderBtn.disabled = false;
    }
}

function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(async () => {
        if (!activeJobId) {
            stopPolling();
            return;
        }
        try {
            const job = await api(`/api/render-jobs/${activeJobId}`);
            updateRenderCard(job);
            if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.status)) {
                stopPolling();
            }
        } catch (error) {
            stopPolling();
        }
    }, 1000);
}

function stopPolling() {
    if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}

startRenderBtn.addEventListener('click', async () => {
    try {
        const job = await api(`/api/projects/${projectId}/render-jobs`, {method: 'POST'});
        activeJobId = job.id;
        updateRenderCard(job);
        startPolling();
    } catch (error) {
        showError(error.message);
    }
});

cancelRenderBtn.addEventListener('click', async () => {
    if (!activeJobId) return;
    try {
        const job = await api(`/api/render-jobs/${activeJobId}/cancel`, {method: 'POST'});
        updateRenderCard(job);
        stopPolling();
    } catch (error) {
        showError(error.message);
    }
});

loadState();
