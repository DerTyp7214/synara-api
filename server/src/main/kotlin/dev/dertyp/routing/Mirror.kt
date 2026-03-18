package dev.dertyp.routing

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.RemoteServerConfig
import dev.dertyp.services.RemoteMirrorService
import io.ktor.http.HttpStatusCode
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.html.*
import org.koin.ktor.ext.inject

fun Route.mirrorRouting() {
    val remoteMirrorService by inject<RemoteMirrorService>()

    route("/admin/mirror") {
        get {
            call.respondHtml {
                head {
                    title("Synara Mirror")
                    script { src = "https://cdn.tailwindcss.com" }
                    style {
                        unsafe {
                            +"""
                                .progress-transition { transition: width 0.5s cubic-bezier(0.4, 0, 0.2, 1); }
                            """.trimIndent()
                        }
                    }
                }
                body("bg-zinc-950 text-slate-200 min-h-screen p-4 md:p-8") {
                    div("max-w-4xl mx-auto space-y-8") {
                        header("flex items-center justify-between") {
                            div {
                                h1("text-3xl font-bold text-amber-400 tracking-tight") { +"Remote Mirror" }
                                p("text-slate-400 mt-1") { +"Synchronize data between Synara instances" }
                            }
                            div("bg-zinc-900 px-3 py-1 rounded-full border border-zinc-800 flex items-center gap-2") {
                                div("w-2 h-2 rounded-full bg-emerald-500 animate-pulse") { id = "status-dot" }
                                span("text-xs font-medium text-slate-300 uppercase tracking-wider") { id = "status-text"; +"System Ready" }
                            }
                        }

                        div("grid grid-cols-1 md:grid-cols-2 gap-8 transition-all duration-500") {
                            id = "main-grid"
                            
                            // Left Column: Configuration or Active Progress
                            div("relative") {
                                div("bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl space-y-6 transition-all duration-500") {
                                    id = "config-section"
                                    h2("text-xl font-semibold flex items-center gap-2") {
                                        unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01" /></svg>""" }
                                        +"Remote Server"
                                    }
                                    
                                    div("space-y-4") {
                                        div {
                                            label("block text-sm font-medium text-slate-400 mb-1.5") { +"Target Host" }
                                            input(type = InputType.text, classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white") { 
                                                id = "host"; value = "localhost"; placeholder = "e.g. synara.example.com"
                                            }
                                        }
                                        
                                        div("grid grid-cols-2 gap-4") {
                                            div {
                                                label("block text-sm font-medium text-slate-400 mb-1.5") { +"Port" }
                                                input(type = InputType.number, classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white") { 
                                                    id = "port"; value = "8080" 
                                                }
                                            }
                                            div {
                                                label("block text-sm font-medium text-slate-400 mb-1.5") { +"Quality" }
                                                select("w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white") {
                                                    id = "quality"
                                                    option { value = "-1"; +"Source (Original)" }
                                                    option { value = "510"; +"Opus Max (510)" }
                                                    option { value = "320"; +"Opus High (320)" }
                                                    option { value = "256"; +"Opus Balanced (256)" }
                                                    option { value = "128"; +"Opus Low (128)" }
                                                }
                                            }
                                        }

                                        div {
                                            label("block text-sm font-medium text-slate-400 mb-1.5") { +"Admin Username" }
                                            input(type = InputType.text, classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white") { id = "username" }
                                        }

                                        div {
                                            label("block text-sm font-medium text-slate-400 mb-1.5") { +"Admin Password" }
                                            input(type = InputType.password, classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white") { id = "password" }
                                        }

                                        label("flex items-center gap-3 cursor-pointer group") {
                                            input(type = InputType.checkBox, classes = "w-5 h-5 rounded border-zinc-700 bg-zinc-800 text-amber-500 focus:ring-amber-500 transition-all") { id = "secure" }
                                            span("text-sm text-slate-300 group-hover:text-white transition-colors") { +"Secure Connection (HTTPS/WSS)" }
                                        }

                                        div("pt-2") {
                                            button(classes = "w-full flex items-center justify-center gap-2 bg-amber-600 hover:bg-amber-500 text-black font-bold py-3 rounded-xl shadow-lg transition-all disabled:opacity-50") {
                                                id = "connect-btn"
                                                attributes["onclick"] = "fetchStats()"
                                                +"Connect & Preview"
                                            }
                                        }
                                    }
                                }

                                // Active Progress Container
                                div("hidden bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl flex flex-col transition-all duration-500") {
                                    id = "active-state"
                                    h2("text-xl font-semibold mb-6 flex items-center gap-2") {
                                        unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg>""" }
                                        +"Mirror Status"
                                    }

                                    div("space-y-6") {
                                        div {
                                            div("flex justify-between items-end mb-2") {
                                                span("text-sm font-medium text-amber-400") { id = "current-task"; +"Initializing" }
                                                span("text-xs font-mono text-slate-500") { id = "task-detail"; +"0 / 0" }
                                            }
                                            div("w-full bg-zinc-800 rounded-full h-3 overflow-hidden") {
                                                div("bg-amber-500 h-full w-0 progress-transition shadow-[0_0_10px_rgba(251,191,36,0.5)]") { id = "progress-fill" }
                                            }
                                        }

                                        // Current Item Progress
                                        div("bg-zinc-900/50 border border-zinc-800 rounded-xl p-4 space-y-3 hidden") {
                                            id = "item-progress-container"
                                            div("flex justify-between items-center") {
                                                span("text-xs font-medium text-slate-300 truncate pr-4") { id = "current-item"; +"-" }
                                                span("text-[10px] font-mono text-slate-500 whitespace-nowrap") { id = "item-percent"; +"" }
                                            }
                                            div("w-full bg-zinc-800/50 rounded-full h-1 overflow-hidden") {
                                                div("bg-amber-400/50 h-full w-0 transition-all duration-200") { id = "item-progress-fill" }
                                            }
                                        }

                                        div("space-y-3") {
                                            h3("text-xs font-bold text-slate-500 uppercase tracking-widest") { +"Synchronization Stages" }
                                            div("space-y-2") {
                                                id = "stages-list"
                                                val stages = listOf(
                                                    "Mirroring Images",
                                                    "Mirroring Artists",
                                                    "Mirroring Artist Aliases",
                                                    "Mirroring Artist Split Aliases",
                                                    "Mirroring Albums",
                                                    "Mirroring Songs",
                                                    "Mirroring Playlists",
                                                    "Mirroring User Playlists"
                                                )
                                                stages.forEachIndexed { index, stage ->
                                                    div("flex items-center gap-3 p-2 rounded-lg bg-zinc-800/30 border border-zinc-700/20 text-sm") {
                                                        id = "stage-$index"
                                                        div("w-2 h-2 rounded-full bg-zinc-700") { id = "stage-dot-$index" }
                                                        span("text-slate-400") { id = "stage-text-$index"; +stage }
                                                    }
                                                }
                                            }
                                        }

                                        div("bg-zinc-800/50 rounded-xl p-4 border border-zinc-700/50 text-sm text-slate-400") {
                                            id = "status-log"
                                            +"Awaiting stream from remote..."
                                        }
                                    }
                                    
                                    div("hidden mt-6 p-4 bg-red-900/20 border border-red-900/50 rounded-xl") {
                                        id = "error-container"
                                        p("text-red-400 text-sm font-medium flex items-center gap-2") {
                                            id = "error-message"
                                            unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z" clip-rule="evenodd" /></svg>""" }
                                        }
                                    }
                                }
                            }

                            // Right Column: Stats Card & Idle State
                            div("space-y-8") {
                                // Stats Card
                                div("bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl") {
                                    id = "stats-card"
                                    
                                    h2("text-lg font-semibold mb-4 flex items-center gap-2") {
                                        unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="text-amber-400 h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" /></svg>""" }
                                        +"Remote Library Preview" 
                                    }

                                    // Connection Info (Compact)
                                    div("hidden mb-6 grid grid-cols-2 gap-2 text-[10px] uppercase font-bold text-slate-500") {
                                        id = "connection-info"
                                        div("bg-zinc-800/30 px-2 py-1 rounded border border-zinc-700/20 truncate") {
                                            id = "info-host"; +"-"
                                        }
                                        div("bg-zinc-800/30 px-2 py-1 rounded border border-zinc-700/20") {
                                            id = "info-quality"; +"-"
                                        }
                                    }
                                    
                                    div("grid grid-cols-2 gap-4") {
                                        listOf("Songs" to "remote-songs", "Albums" to "remote-albums", "Artists" to "remote-artists", "Images" to "remote-images").forEach { (label, id) ->
                                            div("bg-zinc-800/50 p-3 rounded-xl border border-zinc-700/30 text-center") {
                                                p("text-xs text-slate-500 uppercase font-bold") { +label }
                                                p("text-xl font-mono text-amber-300") { this.id = id; +"-" }
                                            }
                                        }
                                    }

                                    button(classes = "w-full mt-6 bg-emerald-600 hover:bg-emerald-500 text-white font-bold py-3 rounded-xl shadow-lg transition-all transform active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed") {
                                        id = "start-btn"
                                        disabled = true
                                        attributes["onclick"] = "startMirror()"
                                        +"Start Synchronization"
                                    }

                                    button(classes = "hidden w-full mt-6 bg-red-600 hover:bg-red-500 text-white font-bold py-3 rounded-xl shadow-lg transition-all transform active:scale-[0.98]") {
                                        id = "stop-btn"
                                        attributes["onclick"] = "stopMirror()"
                                        +"Stop Synchronization"
                                    }

                                    button(classes = "hidden w-full mt-6 bg-zinc-800 hover:bg-zinc-700 text-white font-bold py-3 rounded-xl shadow-lg transition-all transform active:scale-[0.98]") {
                                        id = "reset-btn"
                                        attributes["onclick"] = "resetMirror()"
                                        +"Reset & Reconfigure"
                                    }
                                }

                                div("hidden flex-grow flex flex-col justify-center bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl") {
                                    id = "idle-state"
                                    div("text-center py-8 text-zinc-500") {
                                        p("font-medium") { +"No active synchronization" }
                                    }
                                }
                            }
                        }
                    }

                    script {
                        unsafe {
                            +$$"""
                                function getFormData() {
                                    const data = new URLSearchParams();
                                    data.append('host', document.getElementById('host').value);
                                    data.append('port', document.getElementById('port').value);
                                    data.append('username', document.getElementById('username').value);
                                    data.append('password', document.getElementById('password').value);
                                    data.append('secure', document.getElementById('secure').checked ? 'true' : 'false');
                                    data.append('quality', document.getElementById('quality').value);
                                    return data;
                                }

                                async function fetchStats() {
                                    const btn = document.getElementById('connect-btn');
                                    const startBtn = document.getElementById('start-btn');
                                    btn.disabled = true;
                                    btn.innerText = 'Connecting...';
                                    
                                    try {
                                        const response = await fetch('/admin/mirror/stats', {
                                            method: 'POST',
                                            body: getFormData()
                                        });
                                        
                                        if (!response.ok) throw new Error(await response.text());
                                        
                                        const stats = await response.json();
                                        document.getElementById('remote-songs').innerText = stats.songCount;
                                        document.getElementById('remote-albums').innerText = stats.albumCount;
                                        document.getElementById('remote-artists').innerText = stats.artistCount;
                                        document.getElementById('remote-images').innerText = stats.imagesCount;
                                        
                                        startBtn.disabled = false;
                                    } catch (e) {
                                        alert("Connection failed: " + e.message);
                                        startBtn.disabled = true;
                                    } finally {
                                        btn.disabled = false;
                                        btn.innerText = 'Connect & Preview';
                                    }
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
                                        const response = await fetch('/admin/mirror/start', {
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
                                        alert("Start failed: " + e.message);
                                        btn.disabled = false;
                                        btn.innerText = 'Start Synchronization';
                                    }
                                }

                                async function stopMirror() {
                                    const stopBtn = document.getElementById('stop-btn');
                                    stopBtn.disabled = true;
                                    stopBtn.innerText = 'Stopping...';
                                    
                                    try {
                                        await fetch('/admin/mirror/stop', { method: 'POST' });
                                    } catch (e) {
                                        console.error("Stop failed", e);
                                    }
                                }

                                async function resetMirror() {
                                    const resetBtn = document.getElementById('reset-btn');
                                    resetBtn.disabled = true;
                                    resetBtn.innerText = 'Resetting...';
                                    
                                    try {
                                        await fetch('/admin/mirror/reset', { method: 'POST' });
                                        window.location.reload();
                                    } catch (e) {
                                        alert("Reset failed: " + e.message);
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
                                    
                                    if (progress.isFinished) {
                                        statusDot.className = "w-2 h-2 rounded-full bg-emerald-500";
                                        statusText.innerText = progress.error === "Stopped" ? "Sync Stopped" : "Sync Completed";
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
                                        fill.classList.replace('bg-amber-500', 'bg-emerald-500');
                                        task.innerText = "Mirror Synchronized";
                                        task.classList.add('text-emerald-400');
                                        log.innerText = progress.error === "Stopped" ? "Mirror stopped." : "All data synchronized successfully.";
                                    }
                                }

                                function setupSSE() {
                                    const evtSource = new EventSource("/admin/mirror/progress");
                                    evtSource.onmessage = (event) => {
                                        try {
                                            const progress = JSON.parse(event.data);
                                            updateProgress(progress);
                                        } catch (e) {
                                            console.error("Progress parse error", e);
                                        }
                                    };
                                }
                                setupSSE();
                            """.trimIndent()
                        }
                    }
                }
            }
        }

        post("/start") {
            val params = call.receiveParameters()
            val config = RemoteServerConfig(
                host = params["host"] ?: "",
                port = params["port"]?.toIntOrNull() ?: 80,
                username = params["username"] ?: "",
                password = params["password"] ?: "",
                secure = params["secure"] == "true",
                quality = params["quality"]?.toIntOrNull() ?: -1
            )
            remoteMirrorService.startMirror(config)
            call.respond(HttpStatusCode.OK)
        }

        post("/stop") {
            remoteMirrorService.stopMirror()
            call.respond(HttpStatusCode.OK)
        }

        post("/reset") {
            try {
                remoteMirrorService.resetMirror()
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Reset failed")
            }
        }

        post("/stats") {
            val params = call.receiveParameters()
            val config = RemoteServerConfig(
                host = params["host"] ?: "",
                port = params["port"]?.toIntOrNull() ?: 80,
                username = params["username"] ?: "",
                password = params["password"] ?: "",
                secure = params["secure"] == "true",
                quality = params["quality"]?.toIntOrNull() ?: -1
            )
            try {
                val stats = remoteMirrorService.getRemoteStats(config)
                call.respond(stats)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Failed to fetch stats")
            }
        }

        sse("/progress") {
            remoteMirrorService.getActiveMirrorProgress()?.collectLatest { progress ->
                send(ServerSentEvent(data = ApplicationScope.json.encodeToString(progress)))
            }
        }
    }
}
