import {api, escapeHtml} from './api.js';

let sources = [];
let assets = [];
let projectPage = {page: 0, size: 10, total: 0, totalPages: 0, items: []};
let teamMembers = [];
let templates = [];

document.addEventListener('DOMContentLoaded', async () => {
    initNavigation();
    await Promise.all([
        loadProjects(0),
        loadSources(),
        loadVideos(),
        loadTeamMembers()
    ]);
    renderProjects();
    renderSources();
    renderVideos();
    renderTemplates();
});

function initNavigation() {
    document.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', () => {
            const view = item.dataset.view;
            document.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
            item.classList.add('active');
            document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
            document.getElementById(`view-${view}`).classList.add('active');
        });
    });
}

async function loadTeamMembers() {
    try {
        teamMembers = await api('/api/team-members') || [];
    } catch (e) {
        teamMembers = [];
    }
    renderTeamMembers();
    populatePlayerSelects();
}

function renderTeamMembers() {
    const list = document.getElementById('team-member-list');
    if (teamMembers.length === 0) {
        list.innerHTML = '<div class="empty-tip">暂无队员，请添加</div>';
        return;
    }
    list.innerHTML = teamMembers.map(m => `
    <div class="team-member-item">
      <span>${escapeHtml(m.name)}</span>
      <button onclick="deleteTeamMember(${m.id})">删除</button>
    </div>
  `).join('');
}

window.deleteTeamMember = async function (id) {
    if (!confirm('确定删除此队员吗？')) return;
    try {
        await api(`/api/team-members/${id}`, {method: 'DELETE'});
        teamMembers = teamMembers.filter(m => m.id !== id);
        renderTeamMembers();
        showToast('队员已删除', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
};

document.getElementById('add-team-member').addEventListener('click', async () => {
    const input = document.getElementById('new-team-member-name');
    const name = input.value.trim();
    if (!name) {
        showToast('请输入队员名称', 'error');
        return;
    }
    try {
        const created = await api('/api/team-members', {
            method: 'POST',
            body: JSON.stringify({name})
        });
        teamMembers.push(created);
        renderTeamMembers();
        input.value = '';
        showToast('队员已添加', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
});

document.getElementById('new-team-member-name').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        e.preventDefault();
        document.getElementById('add-team-member').click();
    }
});

async function loadTemplates() {
    try {
        templates = await api('/api/scoreboard-templates') || [];
    } catch (e) {
        templates = [];
    }
}

function renderTemplates() {
    const list = document.getElementById('template-list');
    if (!list) return;
    if (templates.length === 0) {
        list.innerHTML = '<div class="empty-tip">暂无模板，请新建</div>';
        return;
    }
    list.innerHTML = templates.map(t => `
    <div class="template-item" data-id="${t.id}">
      <div class="template-preview" style="background:${t.bgColor};opacity:${t.opacity / 100}">
        <span style="color:${t.textColor}">比分牌预览</span>
      </div>
      <div class="template-info">
        <div class="template-name">${escapeHtml(t.name)}</div>
        <div class="template-meta">透明度: ${t.opacity}% | 背景: ${t.bgColor} | 文字: ${t.textColor}</div>
      </div>
      <div class="template-actions">
        <button onclick="editTemplate(${t.id})">编辑</button>
        <button class="danger" onclick="deleteTemplate(${t.id})">删除</button>
      </div>
    </div>
  `).join('');
}

window.editTemplate = function (id) {
    if (!document.getElementById('template-modal')) return;
    const t = templates.find(t => t.id === id);
    if (!t) return;
    document.getElementById('template-id').value = t.id;
    document.getElementById('template-name').value = t.name;
    document.getElementById('template-opacity').value = t.opacity;
    document.getElementById('template-bg-color').value = t.bgColor;
    document.getElementById('template-bg-color-text').value = t.bgColor;
    document.getElementById('template-text-color').value = t.textColor;
    document.getElementById('template-text-color-text').value = t.textColor;
    document.getElementById('template-modal-title').textContent = '编辑模板';
    document.getElementById('template-modal').classList.remove('hidden');
};

window.deleteTemplate = async function (id) {
    if (!document.getElementById('template-modal')) return;
    if (!confirm('确定删除此模板吗？')) return;
    try {
        await api(`/api/scoreboard-templates/${id}`, {method: 'DELETE'});
        templates = templates.filter(t => t.id !== id);
        renderTemplates();
        showToast('模板已删除', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
};

if (document.getElementById('add-template')) {
    document.getElementById('add-template').addEventListener('click', () => {
        document.getElementById('template-id').value = '';
        document.getElementById('template-form').reset();
        document.getElementById('template-opacity').value = 80;
        document.getElementById('template-bg-color').value = '#000000';
        document.getElementById('template-bg-color-text').value = '#000000';
        document.getElementById('template-text-color').value = '#ffffff';
        document.getElementById('template-text-color-text').value = '#ffffff';
        document.getElementById('template-modal-title').textContent = '新建模板';
        document.getElementById('template-modal').classList.remove('hidden');
    });

    document.getElementById('template-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const id = document.getElementById('template-id').value;
        const data = {
            name: document.getElementById('template-name').value.trim(),
            opacity: parseInt(document.getElementById('template-opacity').value, 10),
            bgColor: document.getElementById('template-bg-color-text').value || '#000000',
            textColor: document.getElementById('template-text-color-text').value || '#ffffff'
        };
        try {
            if (id) {
                await api(`/api/scoreboard-templates/${id}`, {
                    method: 'PUT',
                    body: JSON.stringify(data)
                });
                const idx = templates.findIndex(t => t.id === parseInt(id, 10));
                if (idx >= 0) templates[idx] = {...templates[idx], ...data};
                showToast('模板已更新', 'success');
            } else {
                const created = await api('/api/scoreboard-templates', {
                    method: 'POST',
                    body: JSON.stringify(data)
                });
                templates.push(created);
                showToast('模板已创建', 'success');
            }
            renderTemplates();
            closeTemplateModal();
        } catch (err) {
            showToast(err.message, 'error');
        }
    });

    document.getElementById('template-modal-cancel').addEventListener('click', closeTemplateModal);
    document.getElementById('template-modal').addEventListener('click', (e) => {
        if (e.target.id === 'template-modal') closeTemplateModal();
    });

    function closeTemplateModal() {
        document.getElementById('template-modal').classList.add('hidden');
    }
}

if (document.getElementById('template-bg-color')) {
    ['template-bg-color', 'template-text-color'].forEach(id => {
        const colorInput = document.getElementById(id);
        const textInput = document.getElementById(id + '-text');
        colorInput.addEventListener('input', () => {
            textInput.value = colorInput.value;
        });
        textInput.addEventListener('change', () => {
            if (/^#[0-9a-fA-F]{6}$/.test(textInput.value)) {
                colorInput.value = textInput.value;
            }
        });
    });
}

async function loadProjects(page) {
    try {
        const search = document.getElementById('project-search')?.value?.trim() || '';
        const params = new URLSearchParams({page, size: projectPage.size});
        if (search) params.set('search', search);
        const data = await api(`/api/projects?${params}`);
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
        return `<tr>
      <td class="name clickable" onclick="openProject(${p.id})" title="${escapeHtml(p.name)}">${escapeHtml(p.name)}</td>
      <td class="players">${escapeHtml(p.playerA)} vs ${escapeHtml(p.playerB)}</td>
      <td class="format">${formatLabel}</td>
      <td class="video" title="${escapeHtml(p.videoName || '')}">${escapeHtml(p.videoName || '-')}</td>
      <td class="time">${created}</td>
      <td class="actions"><button onclick="openEditModal(event, ${p.id})">编辑</button><button onclick="exportProjectBackup(event, ${p.id})">导出备份</button><button class="danger delete-btn" onclick="deleteProject(event, ${p.id})">删除</button></td>
    </tr>`;
    }).join('');

    const pager = document.getElementById('project-pager');
    pager.classList.toggle('hidden', projectPage.totalPages <= 1);
    document.getElementById('project-prev').disabled = projectPage.page <= 0;
    document.getElementById('project-next').disabled = projectPage.page >= projectPage.totalPages - 1;
    document.getElementById('project-page-info').textContent =
        `第 ${projectPage.page + 1} / ${Math.max(projectPage.totalPages, 1)} 页`;
}

document.getElementById('project-search').addEventListener('input', () => {
    loadProjects(0);
});

document.getElementById('project-prev').addEventListener('click', () => {
    if (projectPage.page > 0) loadProjects(projectPage.page - 1);
});
document.getElementById('project-next').addEventListener('click', () => {
    if (projectPage.page < projectPage.totalPages - 1) loadProjects(projectPage.page + 1);
});

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

async function exportProjectBackup(event, id) {
    if (event) event.stopPropagation();
    try {
        const response = await fetch(`/api/projects/${id}/backup`);
        if (!response.ok) throw new Error('导出失败');
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `scoresync-project-${id}.json`;
        a.click();
        URL.revokeObjectURL(url);
        showToast('导出成功', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
}

function formatTargetWins(targetWins) {
    const totalGames = targetWins * 2 - 1;
    return `${totalGames}局${targetWins}胜`;
}

async function loadSources() {
    try {
        sources = await api('/api/video-sources') || [];
        renderSources();
    } catch (e) {
    }
}

async function loadVideos() {
    try {
        assets = await api('/api/videos') || [];
        renderVideos();
    } catch (e) {
    }
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
      <button class="secondary" onclick="deleteSource(${s.id})">删除</button>
    </div>
  `).join('');
}

window.scanSource = async function (id) {
    try {
        await api(`/api/video-sources/${id}/scan`, {method: 'POST'});
        assets = await api('/api/videos') || [];
        renderVideos();
        showToast('扫描完成', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
};

window.deleteSource = async function (id) {
    if (!confirm('确定删除此目录吗？')) return;
    try {
        await api(`/api/video-sources/${id}`, {method: 'DELETE'});
        sources = sources.filter(s => s.id !== id);
        renderSources();
        showToast('目录已删除', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
};

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
        const rowClass = canCreate ? 'clickable' : 'unavailable';
        const actionBtn = canCreate
            ? `<button onclick="openCreateModal(${a.id})">创建项目</button>`
            : `<span class="badge">不可读</span>`;
        return `<tr class="${rowClass}" ${canCreate ? `onclick="openCreateModal(${a.id})"` : ''}>
      <td class="name" title="${escapeHtml(a.displayName)}">${escapeHtml(a.displayName)}</td>
      <td class="size">${sizeStr}</td>
      <td class="source">${sourceName}</td>
      <td class="time">${modTime}</td>
      <td class="actions">${actionBtn}</td>
    </tr>`;
    }).join('');
}

function populatePlayerSelects() {
    const selectA = document.getElementById('player-a');
    const selectB = document.getElementById('player-b');
    const options = teamMembers.map(m => `<option value="${escapeHtml(m.name)}">${escapeHtml(m.name)}</option>`).join('');
    selectA.innerHTML = options;
    selectB.innerHTML = options;
    if (teamMembers.length >= 2) {
        selectB.selectedIndex = 1;
    }
}

function openCreateModal(videoId) {
    document.getElementById('project-video-id').value = videoId;
    document.getElementById('project-form').reset();
    document.getElementById('project-video-id').value = videoId;
    document.getElementById('format-preset').value = '3';
    document.getElementById('custom-target-wins-group').classList.add('hidden');
    document.getElementById('modal-submit').disabled = false;

    populatePlayerSelects();

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

    const fileName = asset.displayName.replace(/\.[^.]+$/, '');
    document.getElementById('project-name').value = fileName;

    const fileSize = Number(asset.fileSize) || 1;
    const fallbackMs = Math.max(1, Math.round(fileSize * 8 / 4000000) * 1000);
    const durationMs = Number.isFinite(fallbackMs) && fallbackMs > 0 ? fallbackMs : 1;
    document.getElementById('project-duration-ms').value = durationMs;

    document.getElementById('project-modal').classList.remove('hidden');

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

document.getElementById('export-backup-all').addEventListener('click', async () => {
    try {
        const allProjects = [];
        let page = 0;
        while (true) {
            const data = await api(`/api/projects?page=${page}&size=100`);
            if (!data || !data.items || data.items.length === 0) break;
            allProjects.push(...data.items);
            if (page >= data.totalPages - 1) break;
            page++;
        }
        if (allProjects.length === 0) {
            showToast('暂无项目可导出', 'warning');
            return;
        }
        const backup = {version: 1, projects: allProjects, exportedAt: new Date().toISOString()};
        const blob = new Blob([JSON.stringify(backup, null, 2)], {type: 'application/json'});
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `scoresync-all-projects-${Date.now()}.json`;
        a.click();
        URL.revokeObjectURL(url);
        showToast('导出成功', 'success');
    } catch (e) {
        showToast(e.message, 'error');
    }
});

function populateEditPlayerSelects() {
    const selectA = document.getElementById('edit-player-a');
    const selectB = document.getElementById('edit-player-b');
    const options = teamMembers.map(m => `<option value="${escapeHtml(m.name)}">${escapeHtml(m.name)}</option>`).join('');
    selectA.innerHTML = options;
    selectB.innerHTML = options;
}

window.openEditModal = async function (event, id) {
    if (event) event.stopPropagation();
    const project = projectPage.items.find(p => p.id === id);
    if (!project) {
        showToast('未找到项目', 'error');
        return;
    }
    document.getElementById('edit-project-id').value = id;
    document.getElementById('edit-project-name').value = project.name;
    populateEditPlayerSelects();
    document.getElementById('edit-player-a').value = project.playerA;
    document.getElementById('edit-player-b').value = project.playerB;
    const preset = project.targetWins;
    if (preset >= 2 && preset <= 4) {
        document.getElementById('edit-format-preset').value = preset;
        document.getElementById('edit-custom-target-wins-group').classList.add('hidden');
    } else {
        document.getElementById('edit-format-preset').value = 'custom';
        document.getElementById('edit-custom-target-wins').value = preset;
        document.getElementById('edit-custom-target-wins-group').classList.remove('hidden');
    }
    document.getElementById('edit-project-modal').classList.remove('hidden');
};

function closeEditModal() {
    document.getElementById('edit-project-modal').classList.add('hidden');
}

document.getElementById('edit-modal-cancel').addEventListener('click', closeEditModal);
document.getElementById('edit-project-modal').addEventListener('click', (e) => {
    if (e.target.id === 'edit-project-modal') closeEditModal();
});

document.getElementById('edit-format-preset').addEventListener('change', (e) => {
    const customGroup = document.getElementById('edit-custom-target-wins-group');
    customGroup.classList.toggle('hidden', e.target.value !== 'custom');
});

document.getElementById('edit-project-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const id = Number(document.getElementById('edit-project-id').value);
    const preset = document.getElementById('edit-format-preset').value;
    const targetWins = preset === 'custom'
        ? parseInt(document.getElementById('edit-custom-target-wins').value, 10)
        : parseInt(preset, 10);

    if (isNaN(targetWins) || targetWins < 1) {
        showToast('请输入有效的获胜分数（至少为1）', 'error');
        return;
    }

    try {
        await api(`/api/projects/${id}`, {
            method: 'PUT',
            body: JSON.stringify({
                name: document.getElementById('edit-project-name').value.trim(),
                playerA: document.getElementById('edit-player-a').value,
                playerB: document.getElementById('edit-player-b').value,
                targetWins: targetWins
            })
        });
        closeEditModal();
        showToast('项目已更新', 'success');
        await loadProjects(projectPage.page);
    } catch (err) {
        showToast(err.message, 'error');
    }
});

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
window.deleteSource = deleteSource;
window.openCreateModal = openCreateModal;
window.openProject = openProject;
window.deleteProject = deleteProject;
window.exportProjectBackup = exportProjectBackup;
window.editTemplate = editTemplate;
window.deleteTemplate = deleteTemplate;
