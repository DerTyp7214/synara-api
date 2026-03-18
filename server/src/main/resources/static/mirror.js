function getCookie(name) {
    const value = `; ${document.cookie}`;
    const parts = value.split(`; ${name}=`);
    if (parts.length === 2) return parts.pop().split(';').shift();
}

function hasAuth() {
    return !!getCookie('synara-auth');
}

function showLogin() {
    document.getElementById('login-section').classList.remove('hidden');
    document.getElementById('main-grid').classList.add('hidden');
    document.getElementById('header-logout-btn').classList.add('hidden');
}

function hideLogin() {
    document.getElementById('login-section').classList.add('hidden');
    document.getElementById('main-grid').classList.remove('hidden');
    document.getElementById('header-logout-btn').classList.remove('hidden');
}

async function login() {
    const username = document.getElementById('login-username').value;
    const password = document.getElementById('login-password').value;

    try {
        const response = await fetch('/authenticate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password })
        });

        if (!response.ok) throw new Error("Login failed");

        const data = await response.json();

        // Set cookies for authentication and refresh
        const expiryDate = new Date(data.expiresAt).toUTCString();
        document.cookie = `synara-auth=${data.token}; path=/; expires=${expiryDate}; SameSite=Strict`;
        document.cookie = `synara-refresh=${data.refreshToken}; path=/; max-age=2592000; SameSite=Strict`;

        hideLogin();
        setupSSE();
    } catch (e) {
        alert(e.message);
    }
}

function logout() {
    document.cookie = "synara-auth=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT";
    document.cookie = "synara-refresh=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT";
    window.location.reload();
}

async function tryTokenRefresh() {
    const refreshToken = getCookie('synara-refresh');
    if (!refreshToken) return false;

    try {
        const response = await fetch('/refresh-token', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken })
        });

        if (!response.ok) return false;

        const data = await response.json();
        const expiryDate = new Date(data.expiresAt).toUTCString();
        document.cookie = `synara-auth=${data.token}; path=/; expires=${expiryDate}; SameSite=Strict`;
        document.cookie = `synara-refresh=${data.refreshToken}; path=/; max-age=2592000; SameSite=Strict`;
        return true;
    } catch (e) {
        console.error("Refresh failed", e);
        return false;
    }
}

async function apiFetch(url, options = {}) {
    let response = await fetch(url, options);
    if (response.status === 401) {
        const refreshed = await tryTokenRefresh();
        if (refreshed) {
            response = await fetch(url, options);
        }
    }

    if (response.status === 401) {
        showLogin();
        throw new Error("Unauthorized");
    }
    return response;
}

function getFormData() {
    const data = new URLSearchParams();
    data.append('host', document.getElementById('host').value);
    data.append('port', document.getElementById('port').value);
    data.append('username', document.getElementById('username').value);
    data.append('password', document.getElementById('password').value);
    data.append('secure', document.getElementById('secure').checked ? 'true' : 'false');
    data.append('quality', document.getElementById('quality').value);

    // Filter selections
    const playlists = document.querySelectorAll('#playlist-selection input:checked');
    playlists.forEach(cb => data.append('playlistIds', cb.value));

    const userPlaylists = document.querySelectorAll('#user-playlist-selection input:checked');
    userPlaylists.forEach(cb => data.append('userPlaylistIds', cb.value));

    const likedUsers = document.querySelectorAll('#user-liked-selection input:checked');
    likedUsers.forEach(cb => data.append('likedByUserIds', cb.value));

    return data;
}

async function fetchStats() {
    const btn = document.getElementById('connect-btn');
    const startBtn = document.getElementById('start-btn');
    btn.disabled = true;
    btn.innerText = 'Connecting...';

    const selectionContainers = document.getElementById('selection-containers');
    const playlistSelection = document.getElementById('playlist-selection');
    const userPlaylistSelection = document.getElementById('user-playlist-selection');
    const userLikedSelection = document.getElementById('user-liked-selection');

    playlistSelection.innerHTML = '';
    userPlaylistSelection.innerHTML = '';
    userLikedSelection.innerHTML = '';

    try {
        const formData = getFormData();
        const response = await apiFetch('/admin/mirror/stats', {
            method: 'POST',
            body: formData
        });

        if (!response.ok) throw new Error(await response.text());

        const stats = await response.json();
        document.getElementById('remote-songs').innerText = stats.songCount;
        document.getElementById('remote-albums').innerText = stats.albumCount;
        document.getElementById('remote-artists').innerText = stats.artistCount;
        document.getElementById('remote-images').innerText = stats.imagesCount;

        // Fetch extra data for filtering
        const [usersResp, playlistsResp, userPlaylistsResp] = await Promise.all([
            apiFetch('/admin/mirror/remote-users', { method: 'POST', body: formData }),
            apiFetch('/admin/mirror/remote-playlists', { method: 'POST', body: formData }),
            apiFetch('/admin/mirror/remote-user-playlists', { method: 'POST', body: formData })
        ]);

        if (usersResp.ok) {
            const users = await usersResp.json();
            users.forEach(user => {
                userLikedSelection.appendChild(createCheckboxItem(user.id, user.username));
            });
        }

        if (playlistsResp.ok) {
            const playlists = await playlistsResp.json();
            playlists.forEach(p => {
                playlistSelection.appendChild(createCheckboxItem(p.id, p.name));
            });
        }

        if (userPlaylistsResp.ok) {
            const userPlaylists = await userPlaylistsResp.json();
            userPlaylists.forEach(p => {
                userPlaylistSelection.appendChild(createCheckboxItem(p.id, p.name + ` (${p.user?.username || 'Unknown'})`));
            });
        }

        selectionContainers.classList.remove('hidden');
        startBtn.disabled = false;
    } catch (e) {
        if (e.message !== "Unauthorized") alert("Connection failed: " + e.message);
        startBtn.disabled = true;
        selectionContainers.classList.add('hidden');
    } finally {
        btn.disabled = false;
        btn.innerText = 'Connect & Preview';
    }
}

function createCheckboxItem(id, label) {
    const div = document.createElement('label');
    div.className = 'custom-checkbox flex items-center gap-2 px-2 py-1 rounded hover:bg-zinc-800/50 cursor-pointer transition-colors group';
    div.innerHTML = `
        <input type="checkbox" value="${id}">
        <span class="checkmark"></span>
        <span class="text-xs text-slate-400 group-hover:text-slate-200 truncate">${label}</span>
    `;
    return div;
}

function toggleAll(containerId, checked) {
    const container = document.getElementById(containerId);
    const checkboxes = container.querySelectorAll('input[type="checkbox"]');
    checkboxes.forEach(cb => cb.checked = checked);
}

async function startMirror() {
    const btn = document.getElementById('start-btn');
    const stopBtn = document.getElementById('stop-btn');
    const configSection = document.getElementById('config-section');
    const activeState = document.getElementById('active-state');
    const connInfo = document.getElementById('connection-info');
    const resetBtn = document.getElementById('reset-btn');

    btn.disabled = true;
    btn.innerText = 'Starting Mirror...';

    try {
        const response = await apiFetch('/admin/mirror/start', {
            method: 'POST',
            body: getFormData()
        });
        if (!response.ok) throw new Error(await response.text());

        configSection.classList.add('hidden');
        activeState.classList.remove('hidden');

        btn.classList.add('hidden');
        stopBtn.classList.remove('hidden');
        resetBtn.classList.add('hidden');

        document.getElementById('status-dot').className = "w-2 h-2 rounded-full bg-amber-500 animate-pulse";
        document.getElementById('status-text').innerText = "Sync Active";

        document.getElementById('info-host').innerText = document.getElementById('host').value;
        document.getElementById('info-quality').innerText = 'Quality: ' + document.getElementById('quality').value;
        connInfo.classList.remove('hidden');
    } catch (e) {
        if (e.message !== "Unauthorized") alert("Start failed: " + e.message);
        btn.disabled = false;
        btn.innerText = 'Start Synchronization';
    }
}

async function stopMirror() {
    const stopBtn = document.getElementById('stop-btn');
    stopBtn.disabled = true;
    stopBtn.innerText = 'Stopping...';

    try {
        await apiFetch('/admin/mirror/stop', { method: 'POST' });
    } catch (e) {
        console.error("Stop failed", e);
    }
}

async function resetMirror() {
    const resetBtn = document.getElementById('reset-btn');
    resetBtn.disabled = true;
    resetBtn.innerText = 'Resetting...';

    try {
        await apiFetch('/admin/mirror/reset', { method: 'POST' });
        window.location.reload();
    } catch (e) {
        if (e.message !== "Unauthorized") alert("Reset failed: " + e.message);
        resetBtn.disabled = false;
        resetBtn.innerText = 'Reset & Reconfigure';
    }
}

function updateProgress(progress) {
    const idle = document.getElementById('idle-state');
    const active = document.getElementById('active-state');
    const config = document.getElementById('config-section');
    const fill = document.getElementById('progress-fill');
    const task = document.getElementById('current-task');
    const detail = document.getElementById('task-detail');
    const speed = document.getElementById('current-speed');
    const eta = document.getElementById('current-eta');
    const log = document.getElementById('status-log');
    const errorContainer = document.getElementById('error-container');
    const errorMessage = document.getElementById('error-message');

    const startBtn = document.getElementById('start-btn');
    const stopBtn = document.getElementById('stop-btn');
    const resetBtn = document.getElementById('reset-btn');
    const connInfo = document.getElementById('connection-info');

    const statusDot = document.getElementById('status-dot');
    const statusText = document.getElementById('status-text');

    const itemContainer = document.getElementById('item-progress-container');
    const currentItemText = document.getElementById('current-item');
    const itemPercentText = document.getElementById('item-percent');
    const itemFill = document.getElementById('item-progress-fill');

    if (!progress) {
        config.classList.remove('hidden');
        active.classList.add('hidden');
        idle.classList.add('hidden');

        startBtn.classList.remove('hidden');
        stopBtn.classList.add('hidden');
        resetBtn.classList.add('hidden');
        connInfo.classList.add('hidden');

        statusDot.className = "w-2 h-2 rounded-full bg-emerald-500 animate-pulse";
        statusText.innerText = "System Ready";
        return;
    }

    config.classList.add('hidden');
    active.classList.remove('hidden');
    idle.classList.add('hidden');

    connInfo.classList.remove('hidden');

    if (progress.speed) {
        speed.innerText = progress.speed;
        speed.classList.remove('hidden');
    } else {
        speed.classList.add('hidden');
    }

    if (progress.eta) {
        eta.innerText = 'ETA: ' + progress.eta;
        eta.classList.remove('hidden');
    } else {
        eta.classList.add('hidden');
    }

    if (progress.isFinished) {
        const isActualError = progress.error && progress.error !== "Stopped";

        if (isActualError) {
            statusDot.className = "w-2 h-2 rounded-full bg-red-500 animate-pulse";
            statusText.innerText = "Sync Error";
        } else {
            statusDot.className = "w-2 h-2 rounded-full bg-emerald-500";
            statusText.innerText = progress.error === "Stopped" ? "Sync Stopped" : "Sync Completed";
        }
    } else if (progress.error) {
        statusDot.className = "w-2 h-2 rounded-full bg-red-500 animate-pulse";
        statusText.innerText = "Sync Error";
    } else {
        statusDot.className = "w-2 h-2 rounded-full bg-amber-500 animate-pulse";
        statusText.innerText = "Sync Active";
    }

    const percent = progress.totalItems > 0 ? (progress.processedItems / progress.totalItems) * 100 : 0;
    fill.style.width = `${percent}%`;
    task.innerText = progress.currentTask;
    detail.innerText = `${progress.processedItems} / ${progress.totalItems}`;

    if (progress.currentItem) {
        itemContainer.classList.remove('hidden');
        currentItemText.innerText = progress.currentItem;
        if (progress.currentItemProgress !== null && progress.currentItemProgress !== undefined) {
            const itemPercent = Math.round(progress.currentItemProgress * 100);
            itemPercentText.innerText = `${itemPercent}%`;
            itemFill.style.width = `${itemPercent}%`;
            itemFill.parentElement.classList.remove('hidden');
        } else {
            itemPercentText.innerText = "";
            itemFill.parentElement.classList.add('hidden');
        }
    } else {
        itemContainer.classList.add('hidden');
    }

    const stages = [
        "Mirroring Images",
        "Mirroring Artists",
        "Mirroring Artist Aliases",
        "Mirroring Artist Split Aliases",
        "Mirroring Albums",
        "Mirroring Songs",
        "Mirroring Playlists",
        "Mirroring User Playlists"
    ];

    let currentTaskIndex = stages.indexOf(progress.currentTask);
    if (progress.isFinished) currentTaskIndex = stages.length;

    stages.forEach((stage, index) => {
        const dot = document.getElementById(`stage-dot-${index}`);
        const text = document.getElementById(`stage-text-${index}`);
        const container = document.getElementById(`stage-${index}`);

        if (index < currentTaskIndex) {
            dot.classList.remove('bg-zinc-700', 'bg-amber-500', 'animate-pulse');
            dot.classList.add('bg-emerald-500');
            text.classList.remove('text-slate-400', 'text-amber-400', 'font-bold');
            text.classList.add('text-emerald-400');
            container.classList.add('bg-emerald-500/5', 'border-emerald-500/20');
            container.classList.remove('bg-zinc-800/30', 'border-zinc-700/20', 'bg-amber-500/5', 'border-amber-500/20');
        } else if (index === currentTaskIndex) {
            dot.classList.remove('bg-zinc-700', 'bg-emerald-500');
            dot.classList.add('bg-amber-500', 'animate-pulse');
            text.classList.remove('text-slate-400', 'text-emerald-400');
            text.classList.add('text-amber-400', 'font-bold');
            container.classList.add('bg-amber-500/5', 'border-amber-500/20');
            container.classList.remove('bg-zinc-800/30', 'border-zinc-700/20', 'bg-emerald-500/5', 'border-emerald-500/20');
        } else {
            dot.classList.remove('bg-amber-500', 'bg-emerald-500', 'animate-pulse');
            dot.classList.add('bg-zinc-700');
            text.classList.remove('text-amber-400', 'text-emerald-400', 'font-bold');
            text.classList.add('text-slate-400');
            container.classList.add('bg-zinc-800/30', 'border-zinc-700/20');
            container.classList.remove('bg-amber-500/5', 'border-amber-500/20', 'bg-emerald-500/5', 'border-emerald-500/20');
        }
    });

    if (progress.error) {
        errorContainer.classList.remove('hidden');
        errorMessage.innerText = progress.error;
        fill.classList.replace('bg-amber-500', 'bg-red-500');
        stopBtn.classList.add('hidden');
        resetBtn.classList.remove('hidden');
    } else {
        errorContainer.classList.add('hidden');
        fill.classList.replace('bg-red-500', 'bg-amber-500');

        if (progress.isFinished) {
            stopBtn.classList.add('hidden');
            resetBtn.classList.remove('hidden');
        } else {
            stopBtn.classList.remove('hidden');
            resetBtn.classList.add('hidden');
        }
    }

    if (progress.isFinished) {
        const isActualError = progress.error && progress.error !== "Stopped";

        if (isActualError) {
            fill.classList.replace('bg-amber-500', 'bg-red-500');
            task.innerText = "Mirror Failed";
            task.classList.add('text-red-400');
            log.innerText = "Synchronization failed: " + progress.error;
        } else {
            fill.classList.replace('bg-amber-500', 'bg-emerald-500');
            task.innerText = progress.error === "Stopped" ? "Mirror Stopped" : "Mirror Synchronized";
            task.classList.add('text-emerald-400');
            log.innerText = progress.error === "Stopped" ? "Mirror stopped." : "All data synchronized successfully.";
        }
    }
}

let evtSource = null;
function setupSSE() {
    if (!hasAuth()) return;
    if (evtSource) evtSource.close();

    evtSource = new EventSource(`/admin/mirror/progress`);
    evtSource.onmessage = (event) => {
        try {
            const progress = JSON.parse(event.data);
            updateProgress(progress);
        } catch (e) {
            console.error("Progress parse error", e);
        }
    };
    evtSource.onerror = (e) => {
        console.error("SSE Error", e);
    };
}

if (hasAuth()) {
    hideLogin();
    setupSSE();
} else {
    showLogin();
}
