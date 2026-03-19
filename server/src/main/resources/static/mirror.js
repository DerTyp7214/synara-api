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
    data.append('useProxy', document.getElementById('use-proxy').checked ? 'true' : 'false');
    data.append('proxyInstanceId', document.getElementById('proxy-instance').value);

    // Filter selections
    const playlists = document.querySelectorAll('#playlist-selection input:checked');
    playlists.forEach(cb => data.append('playlistIds', cb.value));

    const userPlaylists = document.querySelectorAll('#user-playlist-selection input:checked');
    userPlaylists.forEach(cb => data.append('userPlaylistIds', cb.value));

    const likedUsers = document.querySelectorAll('#user-liked-selection input:checked');
    likedUsers.forEach(cb => data.append('likedByUserIds', cb.value));

    data.append('targetUserId', document.getElementById('target-user-id').value);

    return data;
}

async function fetchLocalUsers() {
    const optionsContainer = document.getElementById('target-user-options');
    const trigger = document.querySelector('#target-user-select .select-trigger span');

    try {
        const response = await apiFetch('/admin/mirror/local-users');
        if (!response.ok) throw new Error(await response.text());

        const users = await response.json();

        // Clear except first (None)
        const noneOption = optionsContainer.firstElementChild;
        optionsContainer.innerHTML = '';
        optionsContainer.appendChild(noneOption);

        users.forEach(user => {
            const name = user.displayName || user.username;
            const opt = createCustomOption(user.id, name, user.profileImageId);
            optionsContainer.appendChild(opt);
        });
    } catch (e) {
        console.error("Local users fetch failed", e);
    }
}

function toggleProxyFields(show) {
    const fields = document.getElementById('proxy-fields');
    if (show) {
        fields.classList.remove('hidden');
        fetchInstances();
    } else {
        fields.classList.add('hidden');
    }
}

async function fetchInstances() {
    const optionsContainer = document.getElementById('proxy-instance-options');
    const trigger = document.querySelector('#proxy-instance-select .select-trigger span');
    const hiddenInput = document.getElementById('proxy-instance');
    const originalValue = hiddenInput.value;

    optionsContainer.innerHTML = '<div class="select-option" data-value="">Loading instances...</div>';

    try {
        const response = await apiFetch('/admin/mirror/remote-instances', {
            method: 'POST',
            body: getFormData()
        });

        if (!response.ok) throw new Error(await response.text());

        const instances = await response.json();
        optionsContainer.innerHTML = '';

        // Add default option
        const defaultOpt = createCustomOption("", "Select an instance...");
        if (!originalValue) defaultOpt.classList.add('selected');
        optionsContainer.appendChild(defaultOpt);

        instances.forEach(inst => {
            const label = inst.name ? `${inst.name} (${inst.id})` : inst.id;
            const opt = createCustomOption(inst.id, label);
            if (inst.id === originalValue) {
                opt.classList.add('selected');
                trigger.innerText = label;
            }
            optionsContainer.appendChild(opt);
        });

        if (!instances.find(i => i.id === originalValue) && originalValue !== "") {
            hiddenInput.value = "";
            trigger.innerText = "Select an instance...";
        }

    } catch (e) {
        optionsContainer.innerHTML = '<div class="select-option" data-value="">Failed to load instances</div>';
        console.error("Instance fetch failed", e);
    }
}

function createCustomOption(value, label, imageId = null) {
    const div = document.createElement('div');
    div.className = 'select-option flex items-center gap-3';
    div.dataset.value = value;

    let imgHtml = '';
    if (imageId) {
        imgHtml = `<img src="/image/byId/${imageId}?size=64" class="w-5 h-5 flex-shrink-0 rounded-full object-cover border border-zinc-700">`;
    } else if (value !== "") {
        const initials = label.split(' ').map(n => n[0]).join('').substring(0, 2).toUpperCase();
        imgHtml = `<div class="w-5 h-5 flex-shrink-0 rounded-full bg-zinc-800 border border-zinc-700 flex items-center justify-center text-[8px] text-zinc-500 font-bold">${initials}</div>`;
    }

    div.innerHTML = `${imgHtml}<span>${label}</span>`;
    div.onclick = function() { selectOption(this); };
    return div;
}

function toggleSelect(trigger) {
    const container = trigger.parentElement;
    const options = container.querySelector('.select-options');

    // Close other selects
    document.querySelectorAll('.select-options.show').forEach(el => {
        if (el !== options) el.classList.remove('show');
    });
    document.querySelectorAll('.select-trigger.active').forEach(el => {
        if (el !== trigger) el.classList.remove('active');
    });

    options.classList.toggle('show');
    trigger.classList.toggle('active');
}

function selectOption(option) {
    const container = option.closest('.custom-select');
    const trigger = container.querySelector('.select-trigger');
    const triggerText = trigger.querySelector('span');
    const hiddenInput = container.querySelector('input[type="hidden"]');
    const options = container.querySelectorAll('.select-option');

    const value = option.dataset.value;
    const label = option.querySelector('span')?.innerText || option.innerText;

    hiddenInput.value = value;
    triggerText.innerText = label;

    options.forEach(opt => opt.classList.remove('selected'));
    option.classList.add('selected');

    container.querySelector('.select-options').classList.remove('show');
    trigger.classList.remove('active');
}

// Close dropdowns on click outside
window.onclick = function(event) {
    if (!event.target.closest('.custom-select')) {
        document.querySelectorAll('.select-options.show').forEach(el => el.classList.remove('show'));
        document.querySelectorAll('.select-trigger.active').forEach(el => el.classList.remove('active'));
    }
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

        let userMap = {};
        if (usersResp.ok) {
            const users = await usersResp.json();
            users.forEach(user => {
                const name = user.displayName || user.username;
                userMap[user.id] = name;
                userLikedSelection.appendChild(createCheckboxItem(user.id, name, user.profileImageId, true));
            });
        }

        if (playlistsResp.ok) {
            const playlists = await playlistsResp.json();
            playlists.forEach(p => {
                playlistSelection.appendChild(createCheckboxItem(p.id, p.name, p.imageId, true));
            });
        }

        if (userPlaylistsResp.ok) {
            const userPlaylists = await userPlaylistsResp.json();
            userPlaylists.forEach(p => {
                const creatorName = userMap[p.creator] || 'Unknown';
                userPlaylistSelection.appendChild(createCheckboxItem(p.id, p.name + ` (${creatorName})`, p.imageId, true));
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

function createCheckboxItem(id, label, imageId = null, isRemote = false) {
    const div = document.createElement('label');
    div.className = 'custom-checkbox flex items-center gap-3 px-2 py-1 rounded hover:bg-zinc-800/50 cursor-pointer transition-colors group';

    let imgHtml = '';
    if (imageId) {
        let src = `/image/byId/${imageId}?size=64`;
        if (isRemote) {
            const formData = getFormData();
            formData.append('size', '64');
            src = `/admin/mirror/remote-image/${imageId}?${formData.toString()}`;
        }
        imgHtml = `<img src="${src}" class="w-6 h-6 flex-shrink-0 rounded-full object-cover border border-zinc-700">`;
    } else {
        const initials = label.split(' ').map(n => n[0]).join('').substring(0, 2).toUpperCase();
        imgHtml = `<div class="w-6 h-6 flex-shrink-0 rounded-full bg-zinc-800 border border-zinc-700 flex items-center justify-center text-[10px] text-zinc-500 font-bold">${initials}</div>`;
    }

    div.innerHTML = `
        <input type="checkbox" value="${id}">
        <span class="checkmark"></span>
        ${imgHtml}
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

    const allCheckboxes = document.querySelectorAll('#selection-containers input[type="checkbox"]');
    allCheckboxes.forEach(cb => cb.disabled = true);

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
        allCheckboxes.forEach(cb => cb.disabled = false);
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

    const allCheckboxes = document.querySelectorAll('#selection-containers input[type="checkbox"]');

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
        log.innerText = "Awaiting stream from remote...";
        document.getElementById('sync-summary').classList.add('hidden');

        allCheckboxes.forEach(cb => cb.disabled = false);
        return;
    }

    config.classList.add('hidden');
    active.classList.remove('hidden');
    idle.classList.add('hidden');

    connInfo.classList.remove('hidden');

    if (progress.isFinished) {
        allCheckboxes.forEach(cb => cb.disabled = false);
    } else {
        allCheckboxes.forEach(cb => cb.disabled = true);
    }

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

    if (progress.statusMessage) {
        log.innerText = progress.statusMessage;
    }

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
        "Mirroring User Playlists",
        "Syncing User Preferences"
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

            if (!progress.error && progress.syncBreakdown) {
                showSyncSummary(progress.syncBreakdown);
            }
        }
    }
}

function showSyncSummary(breakdown) {
    const summary = document.getElementById('sync-summary');
    const grid = document.getElementById('summary-grid');
    grid.innerHTML = '';

    const items = [
        { label: 'Songs', value: breakdown.songs },
        { label: 'Albums', value: breakdown.albums },
        { label: 'Artists', value: breakdown.artists },
        { label: 'Images', value: breakdown.images },
        { label: 'Playlists', value: breakdown.playlists },
        { label: 'User Playlists', value: breakdown.userPlaylists }
    ];

    items.forEach(item => {
        if (item.value > 0) {
            const div = document.createElement('div');
            div.className = 'flex justify-between items-center bg-zinc-800/30 px-3 py-2 rounded-lg border border-zinc-700/20';
            div.innerHTML = `
                <span class="text-[10px] uppercase font-bold text-slate-500">${item.label}</span>
                <span class="text-sm font-mono text-amber-400">${item.value}</span>
            `;
            grid.appendChild(div);
        }
    });

    if (grid.children.length > 0) {
        summary.classList.remove('hidden');
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
    fetchLocalUsers();
}

if (hasAuth()) {
    hideLogin();
    setupSSE();
} else {
    showLogin();
}
