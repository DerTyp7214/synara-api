package dev.dertyp.routing

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.RemoteServerConfig
import dev.dertyp.services.RemoteMirrorService
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
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
                                [x-cloak] { display: none !important; }
                                .progress-transition { transition: width 0.5s cubic-bezier(0.4, 0, 0.2, 1); }
                            """.trimIndent()
                        }
                    }
                }
                body("bg-zinc-950 text-slate-200 min-h-screen p-4 md:p-8") {
                    div("max-w-4xl mx-auto space-y-8") {
                        header("flex items-center justify-between") {
                            div {
                                h1("text-3xl font-bold text-indigo-400 tracking-tight") { +"Remote Mirror" }
                                p("text-slate-400 mt-1") { +"Synchronize data between Synara instances" }
                            }
                            div("bg-zinc-900 px-3 py-1 rounded-full border border-zinc-800 flex items-center gap-2") {
                                div("w-2 h-2 rounded-full bg-green-500 animate-pulse") {}
                                span("text-xs font-medium text-slate-300 uppercase tracking-wider") { +"System Ready" }
                            }
                        }

                        div("grid grid-cols-1 md:grid-cols-2 gap-8") {
                            // Configuration Form
                            div("bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl") {
                                h2("text-xl font-semibold mb-6 flex items-center gap-2") {
                                    unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-indigo-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>""" }
                                    +"Configuration"
                                }

                                form(action = "/admin/mirror/start", method = FormMethod.post, classes = "space-y-4") {
                                    div {
                                        label("block text-sm font-medium text-slate-400 mb-1.5") { +"Target Host" }
                                        input(type = InputType.text, name = "host", classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none transition-all text-white") {
                                            value = "localhost"
                                            placeholder = "e.g. synara.example.com"
                                        }
                                    }

                                    div("grid grid-cols-2 gap-4") {
                                        div {
                                            label("block text-sm font-medium text-slate-400 mb-1.5") { +"Port" }
                                            input(type = InputType.number, name = "port", classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none transition-all text-white") { value = "8080" }
                                        }
                                        div {
                                            label("block text-sm font-medium text-slate-400 mb-1.5") { +"Quality" }
                                            select("w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none transition-all text-white") {
                                                name = "quality"
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
                                        input(type = InputType.text, name = "username", classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none transition-all text-white")
                                    }

                                    div {
                                        label("block text-sm font-medium text-slate-400 mb-1.5") { +"Admin Password" }
                                        input(type = InputType.password, name = "password", classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-indigo-500 outline-none transition-all text-white")
                                    }

                                    label("flex items-center gap-3 cursor-pointer group") {
                                        input(type = InputType.checkBox, name = "secure", classes = "w-5 h-5 rounded border-zinc-700 bg-zinc-800 text-indigo-500 focus:ring-indigo-500 transition-all")
                                        span("text-sm text-slate-300 group-hover:text-white transition-colors") { +"Secure Connection (HTTPS/WSS)" }
                                    }

                                    button(type = ButtonType.submit, classes = "w-full mt-4 bg-indigo-600 hover:bg-indigo-500 text-white font-bold py-3 rounded-xl shadow-lg shadow-indigo-900/20 transition-all transform active:scale-[0.98]") {
                                        +"Start Synchronization"
                                    }
                                }
                            }

                            // Progress Monitoring
                            div("bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl flex flex-col") {
                                h2("text-xl font-semibold mb-6 flex items-center gap-2") {
                                    unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-indigo-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" /></svg>""" }
                                    +"Active Progress"
                                }

                                div("flex-grow flex flex-col justify-center") {
                                    id = "idle-state"
                                    div("text-center space-y-4") {
                                        div("bg-zinc-800 w-16 h-16 rounded-full mx-auto flex items-center justify-center") {
                                            unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-zinc-600" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 12h.01M12 12h.01M19 12h.01M6 12a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0z" /></svg>""" }
                                        }
                                        p("text-zinc-500 font-medium") { +"Waiting for mirror command..." }
                                    }
                                }

                                div("hidden space-y-6") {
                                    id = "active-state"

                                    div {
                                        div("flex justify-between items-end mb-2") {
                                            span("text-sm font-medium text-indigo-400") { id = "current-task"; +"Initializing" }
                                            span("text-xs font-mono text-slate-500") { id = "task-detail"; +"0 / 0" }
                                        }
                                        div("w-full bg-zinc-800 rounded-full h-3 overflow-hidden") {
                                            div("bg-indigo-500 h-full w-0 progress-transition shadow-[0_0_10px_rgba(99,102,241,0.5)]") { id = "progress-fill" }
                                        }
                                    }

                                    div("bg-zinc-800/50 rounded-xl p-4 border border-zinc-700/50") {
                                        p("text-sm text-slate-400 leading-relaxed") {
                                            id = "status-message"
                                            +"The system is establishing a connection to the remote server and preparing data streams."
                                        }
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
                    }

                    script {
                        unsafe {
                            +$$"""
                                function updateUI(progress) {
                                    const idle = document.getElementById('idle-state');
                                    const active = document.getElementById('active-state');
                                    const fill = document.getElementById('progress-fill');
                                    const task = document.getElementById('current-task');
                                    const detail = document.getElementById('task-detail');
                                    const errorContainer = document.getElementById('error-container');
                                    const errorMessage = document.getElementById('error-message');

                                    if (!progress) {
                                        idle.classList.remove('hidden');
                                        active.classList.add('hidden');
                                        return;
                                    }

                                    idle.classList.add('hidden');
                                    active.classList.remove('hidden');

                                    const percent = progress.totalItems > 0 ? (progress.processedItems / progress.totalItems) * 100 : 0;
                                    fill.style.width = `${percent}%`;
                                    task.innerText = progress.currentTask;
                                    detail.innerText = `${progress.processedItems} / ${progress.totalItems}`;

                                    if (progress.error) {
                                        errorContainer.classList.remove('hidden');
                                        errorMessage.innerText = progress.error;
                                        fill.classList.add('bg-red-500');
                                        fill.classList.remove('bg-indigo-500');
                                    } else {
                                        errorContainer.classList.add('hidden');
                                        fill.classList.remove('bg-red-500');
                                        fill.classList.add('bg-indigo-500');
                                    }

                                    if (progress.isFinished) {
                                        fill.classList.remove('bg-indigo-500');
                                        fill.classList.add('bg-emerald-500');
                                        task.innerText = "Mirror Synchronized";
                                        task.classList.add('text-emerald-400');
                                    }
                                }

                                function setupSSE() {
                                    const evtSource = new EventSource("/admin/mirror/progress");
                                    evtSource.onmessage = (event) => {
                                        try {
                                            const progress = JSON.parse(event.data);
                                            updateUI(progress);
                                        } catch (e) {
                                            console.error("Failed to parse progress", e);
                                        }
                                    };
                                    evtSource.onerror = () => {
                                        // On error, the browser will retry. 
                                        // We don't want to clear UI immediately as it might be transient.
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
                secure = params["secure"] == "on",
                quality = params["quality"]?.toIntOrNull() ?: -1
            )
            remoteMirrorService.startMirror(config)
            call.respondRedirect("/admin/mirror")
        }

        sse("/progress") {
            remoteMirrorService.getActiveMirrorProgress()?.collectLatest { progress ->
                send(ServerSentEvent(data = ApplicationScope.json.encodeToString(progress)))
            }
        }
    }
}
