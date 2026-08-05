export async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: {Accept: 'application/json', ...(options.body ? {'Content-Type': 'application/json'} : {}), ...options.headers},
        ...options
    });
    if (response.status === 204) return null;
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new Error(body?.message || `请求失败（${response.status}）`);
    return body;
}

export function escapeHtml(value) {
    return String(value).replace(/[&<>'"]/g, character => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        "'": '&#39;',
        '"': '&quot;'
    }[character]));
}
