package dev.dertyp.routing

import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.getUser
import dev.dertyp.data.RemoteServerConfig
import dev.dertyp.services.RemoteMirrorService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
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
                            div("bg-zinc-900 px-3 py-1 rounded-full border border-zinc-800 flex items-center gap-4") {
                                div("flex items-center gap-2") {
                                    div("w-2 h-2 rounded-full bg-emerald-500 animate-pulse") { id = "status-dot" }
                                    span("text-xs font-medium text-slate-300 uppercase tracking-wider") { id = "status-text"; +"System Ready" }
                                }
                                button(classes = "hidden text-[10px] font-bold text-amber-500/50 hover:text-amber-500 uppercase tracking-widest transition-colors") {
                                    id = "header-logout-btn"
                                    attributes["onclick"] = "logout()"
                                    +"Logout"
                                }
                            }
                        }

                        // Login Section
                        div("hidden bg-zinc-900 border border-zinc-800 rounded-2xl p-8 shadow-xl max-w-md mx-auto space-y-6") {
                            id = "login-section"
                            h2("text-2xl font-bold text-amber-400 text-center") { +"Admin Login" }
                            div("space-y-4") {
                                div {
                                    label("block text-sm font-medium text-slate-400 mb-1.5") { +"Username" }
                                    input(type = InputType.text, classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white") { id = "login-username" }
                                }
                                div {
                                    label("block text-sm font-medium text-slate-400 mb-1.5") { +"Password" }
                                    input(type = InputType.password, classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white") { 
                                        id = "login-password"
                                        attributes["onkeydown"] = "if(event.key === 'Enter') login()"
                                    }
                                }
                                button(classes = "w-full bg-amber-600 hover:bg-amber-500 text-black font-bold py-3 rounded-xl shadow-lg transition-all") {
                                    attributes["onclick"] = "login()"
                                    +"Sign In"
                                }
                            }
                        }

                        div("hidden grid grid-cols-1 md:grid-cols-2 gap-8 transition-all duration-500") {
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
                                                div("flex items-center gap-3") {
                                                    span("text-sm font-medium text-amber-400") { id = "current-task"; +"Initializing" }
                                                    span("text-[10px] font-mono text-slate-500 bg-zinc-800/50 px-1.5 py-0.5 rounded border border-zinc-700/30 hidden") { id = "current-speed"; +"-" }
                                                }
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

                    script { src = "/static/mirror.js" }
                }
            }
        }

        authenticate("synara-auth") {
            post("/start") {
                val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@post call.respond(HttpStatusCode.Forbidden)

                val params = call.receiveParameters()
                val config = RemoteServerConfig(
                    host = params["host"] ?: "",
                    port = params["port"]?.toIntOrNull() ?: 8080,
                    username = params["username"] ?: "",
                    password = params["password"] ?: "",
                    secure = params["secure"] == "true",
                    quality = params["quality"]?.toIntOrNull() ?: -1
                )
                remoteMirrorService.startMirror(config)
                call.respond(HttpStatusCode.OK)
            }

            post("/stop") {
                val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@post call.respond(HttpStatusCode.Forbidden)

                remoteMirrorService.stopMirror()
                call.respond(HttpStatusCode.OK)
            }

            post("/reset") {
                val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@post call.respond(HttpStatusCode.Forbidden)

                try {
                    remoteMirrorService.resetMirror()
                    call.respond(HttpStatusCode.OK)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Reset failed")
                }
            }

            post("/stats") {
                val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@post call.respond(HttpStatusCode.Forbidden)

                val params = call.receiveParameters()
                val config = RemoteServerConfig(
                    host = params["host"] ?: "",
                    port = params["port"]?.toIntOrNull() ?: 8080,
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
                val user = call.getUser() ?: return@sse call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@sse call.respond(HttpStatusCode.Forbidden)

                remoteMirrorService.getActiveMirrorProgress()?.collectLatest { progress ->
                    send(ServerSentEvent(data = ApplicationScope.json.encodeToString(progress)))
                }
            }
        }
    }
}
