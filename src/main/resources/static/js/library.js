import {api, escapeHtml} from './api.js';

let sources = [];
let assets = [];
let projectPage = {page: 0, size: 10, total: 0, totalPages: 0, items: []};

document.addEventListener('DOMContentLoaded', async () => {
    const [sourcesRes, assetsRes] = await Promise.all([
        api('/api/video-sources'),
        api('/api/videos')
    ]);
    sources = sourcesRes || [];
    assets = assetsRes || [];
    renderSources();
    renderVideos();
    await loadProjects(0);
});

async function loadProjects(page) {
    try {
        const data = await api(`/api/projects?page=${page}&size=${projectPage.size}`);
        projectPage = data || {page: 0, size: projectPage.size, total: 0, totalPages: 0, items: []};
        renderProjects();
    } catch (e) {
        showToast(e.message, 'error');
    }
}

function renderProjects() {
    document.getElementById('project-count').textContent = projectPage.total;
    const tbody = document.getElementById('project-list');
    if (!projectPage.items || projectPage.items.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;color:var(--text-dim)">暂无项目</td></tr>';
        document.getElementById('project-pager').classList.add('hidden');
        return;
    }
    tbody.innerHTML = projectPage.items.map(p => {
        const created = p.createdAt ? new Date(p.createdAt).toLocaleString('zh-CN') : '-';
        const formatLabel = formatTargetWins(p.targetWins);
        return `<tr class="clickable" onclick="openProject(${p.id})">
      <td class="name" title="${escapeHtml(p.name)}">${escapeHtml(p.name)}</td>
      <td class="players">${escapeHtml(p.playerA)} vs ${escapeHtml(p.playerB)}</td>
      <td class="format">${formatLabel}</td>
      <td class="video" title="${escapeHtml(p.videoName || '')}">${escapeHtml(p.videoName || '-')}</td>
      <td class="time">${created}</td>
      <td class="actions"><button class="danger delete-btn" onclick="deleteProject(event, ${p.id})">删除</button></td>
    </tr>`;
    }).join('');

    const pager = document.getElementById('project-pager');
    pager.classList.toggle('hidden', projectPage.totalPages <= 1);
    document.getElementById('project-prev').disabled = projectPage.page <= 0;
    document.getElementById('project-next').disabled = projectPage.page >= projectPage.totalPages - 1;
    document.getElementById('project-page-info').textContent =
        `第 ${projectPage.page + 1} / ${Math.max(projectPage.totalPages, 1)} 页`;
}

function openProject(id) {
    location.assign(`/editor.html?projectId=${id}`);
}

async function deleteProject(event, id) {
    if (event) event.stopPropagation();
    const item = projectPage.items.find(p => p.id === id);
    const name = item ? item.name : `#${id}`;
    if (!confirm(`确定删除项目「${name}」吗？此操作不可恢复。`)) return;
    try {
        await api(`/api/projects/${id}`, {method: 'DELETE'});
        showToast('项目已删除', 'success');
        await loadProjects(projectPage.page);
    } catch (e) {
        showToast(e.message, 'error');
    }
}

function formatTargetWins(targetWins) {
    const totalGames = targetWins * 2 - 1;
    return `${totalGames}局${targetWins}胜`;
}

document.getElementById('add-source').addEventListener('click', async () => {
    const pathInput = document.getElementById('source-path');
    const path = pathInput.value.trim();
    if (!path) return;
    try {
        const source = await api('/api/video-sources', {
            method: 'POST',
            body: JSON.stringify({rootPath: path})
        });
        sources.push(source);
        pathInput.value = '';
        renderSources();
        showToast('目录添加成功', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
});

document.getElementById('scan-all').addEventListener('click', async () => {
    const btn = document.getElementById('scan-all');
    btn.disabled = true;
    try {
        for (const source of sources) {
            await api(`/api/video-sources/${source.id}/scan`, {method: 'POST'});
        }
        assets = await api('/api/videos') || [];
        renderVideos();
        showToast('扫描完成', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    } finally {
        btn.disabled = false;
    }
});

document.getElementById('pick-local-video').addEventListener('click', async () => {
    try {
        const asset = await api('/api/videos/pick-local', {method: 'POST'});
        assets.push(asset);
        renderVideos();
        showToast('视频添加成功', 'success');
    } catch (e) {
        if (e.message.includes('headless')) {
            showToast('当前环境不支持选择本地文件（headless模式）', 'warning');
        } else {
            showToast(e.message, 'error');
        }
    }
});

document.getElementById('format-preset').addEventListener('change', (e) => {
    const customGroup = document.getElementById('custom-target-wins-group');
    customGroup.classList.toggle('hidden', e.target.value !== 'custom');
});

document.getElementById('project-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const preset = document.getElementById('format-preset').value;
    const targetWins = preset === 'custom'
        ? parseInt(document.getElementById('custom-target-wins').value, 10)
        : parseInt(preset, 10);

    if (isNaN(targetWins) || targetWins < 1) {
        showToast('请输入有效的获胜分数（至少为1）', 'error');
        return;
    }

    try {
        const project = await api('/api/projects', {
            method: 'POST',
            body: JSON.stringify({
                name: form['project-name'].value.trim(),
                videoId: Number(form['project-video-id'].value),
                durationMs: Number(form['project-duration-ms'].value),
                playerA: form['player-a'].value.trim(),
                playerB: form['player-b'].value.trim(),
                targetWins: targetWins
            })
        });
        location.assign(`/editor.html?projectId=${project.project.id}`);
    } catch (err) {
        showToast(err.message, 'error');
    }
});

document.getElementById('modal-cancel').addEventListener('click', closeModal);
document.getElementById('project-modal').addEventListener('click', (e) => {
    if (e.target.id === 'project-modal') closeModal();
});

document.getElementById('import-backup').addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    try {
        const text = await file.text();
        const doc = JSON.parse(text);
        const result = await api('/api/projects/import', {
            method: 'POST',
            body: JSON.stringify(doc)
        });
        location.assign(`/editor.html?projectId=${result.project.id}`);
    } catch (err) {
        if (err.message.includes('JSON')) {
            showToast('备份文件不是有效 JSON', 'error');
        } else {
            showToast(err.message, 'error');
        }
    }
    e.target.value = '';
});

function renderSources() {
    const list = document.getElementById('source-list');
    if (sources.length === 0) {
        list.innerHTML = '<div style="font-size:0.875rem;color:var(--text-dim)">暂无目录，请添加</div>';
        return;
    }
    list.innerHTML = sources.map(s => `
    <div class="source-item">
      <span class="path" title="${escapeHtml(s.rootPath)}">${escapeHtml(s.rootPath)}</span>
      <button onclick="scanSource(${s.id})">扫描</button>
    </div>
  `).join('');
}

async function scanSource(id) {
    try {
        await api(`/api/video-sources/${id}/scan`, {method: 'POST'});
        assets = await api('/api/videos') || [];
        renderVideos();
        showToast('扫描完成', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
}

function renderVideos() {
    document.getElementById('video-count').textContent = assets.length;
    const tbody = document.getElementById('video-list');
    if (assets.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-dim)">暂无视频</td></tr>';
        return;
    }
    tbody.innerHTML = assets.map(a => {
        const source = sources.find(s => s.id === a.sourceId);
        const sourceName = source ? escapeHtml(source.rootPath.split(/[\\/]/).pop()) : '本地';
        const modTime = a.modifiedAt ? new Date(a.modifiedAt).toLocaleString('zh-CN') : '-';
        const sizeStr = formatSize(a.fileSize);
        const canCreate = a.readable !== false;
        const rowClass = canCreate ? '' : 'unavailable';
        const actionBtn = canCreate
            ? `<button onclick="openCreateModal(${a.id})">创建项目</button>`
            : `<span class="badge">不可读</span>`;
        return `<tr class="${rowClass}">
      <td class="name" title="${escapeHtml(a.displayName)}">${escapeHtml(a.displayName)}</td>
      <td class="size">${sizeStr}</td>
      <td class="source">${sourceName}</td>
      <td class="time">${modTime}</td>
      <td class="actions">${actionBtn}</td>
    </tr>`;
    }).join('');
}

function openCreateModal(videoId) {
    document.getElementById('project-video-id').value = videoId;
    document.getElementById('project-form').reset();
    document.getElementById('project-video-id').value = videoId;
    document.getElementById('format-preset').value = '3';
    document.getElementById('custom-target-wins-group').classList.add('hidden');
    document.getElementById('modal-submit').disabled = false;

    const asset = assets.find(a => a.id === videoId);
    if (!asset) {
        showToast('未找到对应的视频资产，请刷新页面后重试', 'error');
        closeModal();
        return;
    }
    if (typeof asset.fileSize !== 'number' || asset.fileSize < 1) {
        showToast('视频文件信息无效，请重新扫描目录', 'error');
        closeModal();
        return;
    }

    // 兜底时长：按文件大小估算（约4Mbps），保证为正数以通过后端校验；真实时长异步覆盖
    const fileSize = Number(asset.fileSize) || 1;
    const fallbackMs = Math.max(1, Math.round(fileSize * 8 / 4000000) * 1000);
    const durationMs = Number.isFinite(fallbackMs) && fallbackMs > 0 ? fallbackMs : 1;
    document.getElementById('project-duration-ms').value = durationMs;

    // 立即显示弹窗，不阻塞在视频元数据加载上
    document.getElementById('project-modal').classList.remove('hidden');

    // 异步尝试获取真实时长，成功则覆盖估算值；失败不影响创建流程
    const probe = document.createElement('video');
    probe.preload = 'metadata';
    probe.src = `/api/media/${videoId}`;
    probe.onloadedmetadata = () => {
        const ms = Math.round(probe.duration * 1000);
        if (ms > 0 && Number.isFinite(ms)) {
            document.getElementById('project-duration-ms').value = ms;
        }
    };
    probe.load();
}

function closeModal() {
    document.getElementById('project-modal').classList.add('hidden');
    document.getElementById('modal-submit').disabled = false;
}

function formatSize(bytes) {
    if (!bytes || bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function showToast(message, type = 'success') {
    const host = document.getElementById('toast-host');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    host.appendChild(toast);
    setTimeout(() => toast.remove(), 3000);
}

window.scanSource = scanSource;
window.openCreateModal = openCreateModal;
window.openProject = openProject;
window.deleteProject = deleteProject;

document.getElementById('project-prev').addEventListener('click', () => {
    if (projectPage.page > 0) loadProjects(projectPage.page - 1);
});
document.getElementById('project-next').addEventListener('click', () => {
    if (projectPage.page < projectPage.totalPages - 1) loadProjects(projectPage.page + 1);
});
