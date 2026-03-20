package dev.dertyp.routing

import com.ucasoft.ktor.simpleCache.cacheOutput
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.getUser
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.RemoteServerConfig
import dev.dertyp.services.RemoteMirrorService
import dev.dertyp.services.UserService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.conflate
import kotlinx.html.*
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.days

@OptIn(FlowPreview::class)
fun Route.mirrorRouting() {
    val remoteMirrorService by inject<RemoteMirrorService>()

    fun Parameters.toMirrorConfig() = RemoteServerConfig(
        host = this["host"] ?: "",
        port = this["port"]?.toIntOrNull() ?: 8080,
        username = this["username"] ?: "",
        password = this["password"] ?: "",
        secure = this["secure"] == "true",
        quality = this["quality"]?.toIntOrNull() ?: -1,
        playlistIds = this.getAll("playlistIds")?.mapNotNull { it.toUUIDOrNull() },
        userPlaylistIds = this.getAll("userPlaylistIds")?.mapNotNull { it.toUUIDOrNull() },
        likedByUserIds = this.getAll("likedByUserIds")?.mapNotNull { it.toUUIDOrNull() },
        useProxy = this["useProxy"] == "true",
        proxyInstanceId = this["proxyInstanceId"],
        targetUserId = this["targetUserId"]?.toUUIDOrNull(),
        isImport = this["isImport"] == "true"
    )

    route("/admin/mirror") {
        get {
            call.respondHtml {
                head {
                    title("Synara Mirror")
                    script { src = "https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4" }
                    style("text/tailwindcss") {
                        unsafe {
                            +"""
                            @import "tailwindcss";

                            @layer base {
                                ::selection {
                                    @apply bg-amber-400 text-black;
                                }
                            }

                            @layer components {
                                .custom-scrollbar {
                                    @apply overflow-y-auto;
                                    &::-webkit-scrollbar { @apply w-1.5 bg-transparent; }
                                    &::-webkit-scrollbar-track { @apply bg-transparent border-none; }
                                    &::-webkit-scrollbar-thumb { @apply bg-zinc-700 rounded-full; }
                                    &::-webkit-scrollbar-thumb:hover { @apply bg-zinc-600; }
                                }

                                .custom-checkbox {
                                    @apply relative flex items-center cursor-pointer select-none;
                                    & input { @apply absolute opacity-0 h-0 w-0; }
                                    & .checkmark {
                                        @apply h-3.5 w-3.5 flex-shrink-0 bg-zinc-900 border border-zinc-600 rounded transition-all flex items-center justify-center;
                                    }
                                    &:hover .checkmark { @apply border-amber-400; }
                                    & input:checked ~ .checkmark { @apply bg-amber-600 border-amber-600; }
                                    & .checkmark:after {
                                        content: "";
                                        @apply hidden w-1.5 h-2.5 border-white border-r-2 border-b-2 rotate-45 -translate-y-[1px];
                                    }
                                    & input:checked ~ .checkmark:after { @apply block; }
                                    & input:disabled ~ .checkmark,
                                    & input:disabled ~ span {
                                        @apply opacity-50 cursor-not-allowed bg-zinc-800;
                                    }
                                }

                                .custom-select {
                                    @apply relative w-full;
                                }

                                .select-trigger {
                                    @apply w-full bg-zinc-800 border border-zinc-700 rounded-lg px-4 py-2 flex items-center justify-between cursor-pointer transition-all text-white text-sm min-h-[42px];
                                    &:hover { @apply border-zinc-600; }
                                    &.active { @apply border-amber-500 ring-2 ring-amber-500/20; }
                                    & svg { @apply transition-transform duration-200; }
                                    &.active svg { @apply rotate-180; }
                                }

                                .select-options {
                                    @apply absolute top-full left-0 right-0 bg-zinc-900 border border-zinc-700 rounded-lg mt-1 p-1 z-50 hidden shadow-2xl max-h-60 overflow-y-auto;
                                    &.show { @apply block; }
                                }

                                .select-option {
                                    @apply px-3 py-2 rounded-md cursor-pointer transition-all text-slate-400 text-sm;
                                    &:hover { @apply bg-zinc-800 text-white; }
                                    &.selected { @apply bg-amber-500/10 text-amber-400 font-medium; }
                                }
                            }
                            """
                        }
                    }
                }
                body("bg-zinc-950 text-slate-200 min-h-screen p-4 md:p-8 selection:bg-amber-400 selection:text-black") {
                    style = "color-scheme: dark;"
                    div("max-w-4xl mx-auto space-y-8") {
                        header("flex items-center justify-between select-none") {
                            div {
                                h1("text-3xl font-bold text-amber-400 tracking-tight") { +"Remote Mirror" }
                                p("text-slate-400 mt-1") { +"Synchronize data between Synara instances" }
                            }
                            div("bg-zinc-900 px-3 py-1 rounded-full border border-zinc-800 flex items-center gap-4") {
                                div("flex items-center gap-2") {
                                    div("w-2 h-2 rounded-full bg-emerald-500 animate-pulse") {
                                        id = "status-dot"
                                    }
                                    span("text-xs font-medium text-slate-300 uppercase tracking-wider") {
                                        id = "status-text"; +"System Ready"
                                    }
                                }
                                button(classes = "hidden text-[10px] font-bold text-amber-500/50 hover:text-amber-500 uppercase tracking-widest transition-colors select-none") {
                                    id = "header-logout-btn"
                                    attributes["onclick"] = "logout()"
                                    +"Logout"
                                }
                            }
                        }

                        // Login Section
                        div("hidden bg-zinc-900 border border-zinc-800 rounded-2xl p-8 shadow-xl max-w-md mx-auto space-y-6 select-none") {
                            id = "login-section"
                            h2("text-2xl font-bold text-amber-400 text-center") { +"Admin Login" }
                            div("space-y-4") {
                                div {
                                    label("block text-sm font-medium text-slate-400 mb-1.5") { +"Username" }
                                    input(
                                        type = InputType.text,
                                        classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white select-text"
                                    ) { id = "login-username" }
                                }
                                div {
                                    label("block text-sm font-medium text-slate-400 mb-1.5") { +"Password" }
                                    input(
                                        type = InputType.password,
                                        classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white select-text"
                                    ) {
                                        id = "login-password"
                                        attributes["onkeydown"] =
                                            "if(event.key === 'Enter') login()"
                                    }
                                }
                                button(classes = "w-full bg-amber-600 hover:bg-amber-500 text-black font-bold py-3 rounded-xl shadow-lg transition-all active:scale-[0.98]") {
                                    attributes["onclick"] = "login()"
                                    +"Sign In"
                                }
                            }
                        }

                        div("hidden grid grid-cols-1 md:grid-cols-2 gap-8 transition-all duration-500") {
                            id = "main-grid"

                            // Left Column: Configuration or Active Progress
                            div("relative") {
                                div("bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl space-y-6 transition-all duration-500 select-none") {
                                    id = "config-section"
                                    h2("text-xl font-semibold flex items-center gap-2") {
                                        unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01" /></svg>""" }
                                        +"Remote Server"
                                    }

                                    div("space-y-4") {
                                        div {
                                            label("block text-sm font-medium text-slate-400 mb-1.5") { +"Target Host" }
                                            input(
                                                type = InputType.text,
                                                classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white select-text"
                                            ) {
                                                id = "host"; value = "localhost"; placeholder =
                                                "e.g. synara.example.com"
                                            }
                                        }

                                        div("grid grid-cols-2 gap-4") {
                                            div {
                                                label("block text-sm font-medium text-slate-400 mb-1.5") { +"Port" }
                                                input(
                                                    type = InputType.number,
                                                    classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white select-text"
                                                ) {
                                                    id = "port"; value = "8080"
                                                }
                                            }
                                            div {
                                                div("flex items-center gap-2 mb-1.5") {
                                                    label("block text-sm font-medium text-slate-400") { +"Quality" }
                                                    div("group relative cursor-help") {
                                                        unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-500 hover:text-amber-400 transition-colors" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>""" }
                                                        div("absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-64 p-3 bg-zinc-900 text-xs text-slate-300 rounded-xl shadow-2xl border border-zinc-800 opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-[60] font-normal normal-case") {
                                                            +"Determines the audio quality of the mirrored songs. 'Source' will mirror the original file without transcoding."
                                                        }
                                                    }
                                                }
                                                div("custom-select") {
                                                    id = "quality-select"
                                                    input(type = InputType.hidden) {
                                                        id = "quality"; value = "-1"
                                                    }
                                                    div("select-trigger") {
                                                        attributes["onclick"] = "toggleSelect(this)"
                                                        span { +"Source (Original)" }
                                                        unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-500" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" /></svg>""" }
                                                    }
                                                    div("select-options custom-scrollbar") {
                                                        div("select-option selected") {
                                                            attributes["data-value"] =
                                                                "-1"; attributes["onclick"] =
                                                            "selectOption(this)"; +"Source (Original)"
                                                        }
                                                        div("select-option") {
                                                            attributes["data-value"] =
                                                                "510"; attributes["onclick"] =
                                                            "selectOption(this)"; +"Opus Max (510)"
                                                        }
                                                        div("select-option") {
                                                            attributes["data-value"] =
                                                                "320"; attributes["onclick"] =
                                                            "selectOption(this)"; +"Opus High (320)"
                                                        }
                                                        div("select-option") {
                                                            attributes["data-value"] =
                                                                "256"; attributes["onclick"] =
                                                            "selectOption(this)"; +"Opus Balanced (256)"
                                                        }
                                                        div("select-option") {
                                                            attributes["data-value"] =
                                                                "128"; attributes["onclick"] =
                                                            "selectOption(this)"; +"Opus Low (128)"
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        div {
                                            label("block text-sm font-medium text-slate-400 mb-1.5") { +"Admin Username" }
                                            input(
                                                type = InputType.text,
                                                classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white select-text"
                                            ) { id = "username" }
                                        }

                                        div {
                                            label("block text-sm font-medium text-slate-400 mb-1.5") { +"Admin Password" }
                                            input(
                                                type = InputType.password,
                                                classes = "w-full bg-zinc-800 border-zinc-700 rounded-lg px-4 py-2 focus:ring-2 focus:ring-amber-500 outline-none transition-all text-white select-text"
                                            ) { id = "password" }
                                        }

                                        div("flex flex-col gap-3") {
                                            label("custom-checkbox flex items-center gap-3 group") {
                                                input(type = InputType.checkBox) { id = "is-import" }
                                                span("checkmark")
                                                span("text-sm text-slate-300 group-hover:text-white transition-colors") { +"Import Mode (Merge with existing data)" }
                                            }

                                            label("custom-checkbox flex items-center gap-3 group") {
                                                input(type = InputType.checkBox) { id = "secure" }
                                                span("checkmark")
                                                span("text-sm text-slate-300 group-hover:text-white transition-colors") { +"Secure Connection (HTTPS/WSS)" }
                                            }

                                            label("custom-checkbox flex items-center gap-3 group") {
                                                input(type = InputType.checkBox) {
                                                    id = "use-proxy"
                                                    attributes["onclick"] =
                                                        "toggleProxyFields(this.checked)"
                                                }
                                                span("checkmark")
                                                span("text-sm text-slate-300 group-hover:text-white transition-colors") { +"Use Proxy" }
                                            }
                                        }

                                        div("hidden space-y-4 pt-2 border-t border-zinc-800") {
                                            id = "proxy-fields"
                                            div {
                                                div("flex items-center gap-2 mb-1.5") {
                                                    label("block text-sm font-medium text-slate-400") { +"Proxy Instance" }
                                                    div("group relative cursor-help") {
                                                        unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-500 hover:text-amber-400 transition-colors" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>""" }
                                                        div("absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-64 p-3 bg-zinc-900 text-xs text-slate-300 rounded-xl shadow-2xl border border-zinc-800 opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-[60] font-normal normal-case") {
                                                            +"If using a proxy, select which instance to route the traffic through."
                                                        }
                                                    }
                                                }
                                                div("flex gap-2") {
                                                    div("custom-select flex-1") {
                                                        id = "proxy-instance-select"
                                                        input(type = InputType.hidden) {
                                                            id = "proxy-instance"; value = ""
                                                        }
                                                        div("select-trigger") {
                                                            attributes["onclick"] =
                                                                "toggleSelect(this)"
                                                            span { +"Select an instance..." }
                                                            unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-500" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" /></svg>""" }
                                                        }
                                                        div("select-options custom-scrollbar") {
                                                            id = "proxy-instance-options"
                                                            div("select-option selected") {
                                                                attributes["data-value"] =
                                                                    ""; attributes["onclick"] =
                                                                "selectOption(this)"; +"Select an instance..."
                                                            }
                                                        }
                                                    }
                                                    button(classes = "bg-zinc-800 hover:bg-zinc-700 p-2 rounded-lg border border-zinc-700 transition-all") {
                                                        attributes["onclick"] = "fetchInstances()"
                                                        unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg>""" }
                                                    }
                                                }
                                            }
                                        }

                                        div {
                                            div("flex items-center gap-2 mb-1.5") {
                                                label("block text-sm font-medium text-slate-400") { +"Target Local User" }
                                                div("group relative cursor-help") {
                                                    unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-500 hover:text-amber-400 transition-colors" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>""" }
                                                    div("absolute bottom-full left-1/2 -translate-x-1/2 mb-2 w-64 p-3 bg-zinc-900 text-xs text-slate-300 rounded-xl shadow-2xl border border-zinc-800 opacity-0 group-hover:opacity-100 transition-opacity pointer-events-none z-[60] font-normal normal-case") {
                                                        +"Selecting a local user here will map all imported data (like playlists or likes) to the chosen local user instead of the user who originally created them on the remote instance."
                                                    }
                                                }
                                            }
                                            div("custom-select") {
                                                id = "target-user-select"
                                                input(type = InputType.hidden) {
                                                    id = "target-user-id"; value = ""
                                                }
                                                div("select-trigger") {
                                                    attributes["onclick"] = "toggleSelect(this)"
                                                    span { +"Select local user (optional)..." }
                                                    unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-slate-500" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" /></svg>""" }
                                                }
                                                div("select-options custom-scrollbar") {
                                                    id = "target-user-options"
                                                    div("select-option selected") {
                                                        attributes["data-value"] =
                                                            ""; attributes["onclick"] =
                                                        "selectOption(this)"; +"None"
                                                    }
                                                }
                                            }
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
                                div("hidden bg-zinc-900 border border-zinc-800 rounded-2xl p-6 shadow-xl flex flex-col transition-all duration-500 select-none") {
                                    id = "active-state"
                                    h2("text-xl font-semibold mb-6 flex items-center gap-2") {
                                        unsafe { +"""<svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg>""" }
                                        +"Mirror Status"
                                    }

                                    div("space-y-6") {
                                        div {
                                            div("flex justify-between items-end mb-2") {
                                                div("flex items-center gap-3") {
                                                    span("text-sm font-medium text-amber-400") {
                                                        id = "current-task"; +"Initializing"
                                                    }
                                                    div("flex items-center gap-1.5") {
                                                        span("text-[10px] font-mono text-slate-500 bg-zinc-800/50 px-1.5 py-0.5 rounded border border-zinc-700/30 hidden select-text") {
                                                            id = "current-speed"; +"-"
                                                        }
                                                        span("text-[10px] font-mono text-slate-500 bg-zinc-800/50 px-1.5 py-0.5 rounded border border-zinc-700/30 hidden select-text") {
                                                            id = "current-eta"; +"-"
                                                        }
                                                    }
                                                }
                                                span("text-xs font-mono text-slate-500 select-text") {
                                                    id = "task-detail"; +"0 / 0"
                                                }
                                            }
                                            div("w-full bg-zinc-800 rounded-full h-3 overflow-hidden") {
                                                div("bg-amber-500 h-full w-0 shadow-[0_0_10px_rgba(251,191,36,0.5)]") {
                                                    id = "progress-fill"
                                                }
                                            }
                                        }

                                        // Current Item Progress
                                        div("bg-zinc-900/50 border border-zinc-800 rounded-xl p-4 space-y-3 hidden") {
                                            id = "item-progress-container"
                                            div("flex justify-between items-center") {
                                                span("text-xs font-medium text-slate-300 truncate pr-4 select-text") {
                                                    id = "current-item"; +"-"
                                                }
                                                span("text-[10px] font-mono text-slate-500 whitespace-nowrap select-text") {
                                                    id = "item-percent"; +""
                                                }
                                            }
                                            div("w-full bg-zinc-800/50 rounded-full h-1 overflow-hidden") {
                                                div("bg-amber-400/50 h-full w-0 transition-all duration-200") {
                                                    id = "item-progress-fill"
                                                }
                                            }
                                        }

                                        div("space-y-3") {
                                            h3("text-xs font-bold text-slate-500 uppercase tracking-widest") { +"Synchronization Stages" }
                                            div("space-y-2") {
                                                id = "stages-list"
                                                val stages = listOf(
                                                    "Analyzing Selection",
                                                    "Mirroring Images",
                                                    "Mirroring Artists",
                                                    "Mirroring Artist Aliases",
                                                    "Mirroring Artist Split Aliases",
                                                    "Mirroring Albums",
                                                    "Mirroring Songs",
                                                    "Mirroring Playlists",
                                                    "Mirroring User Playlists",
                                                    "Syncing User Preferences"
                                                )
                                                stages.forEachIndexed { index, stage ->
                                                    div("flex items-center gap-3 p-2 rounded-lg bg-zinc-800/30 border border-zinc-700/20 text-sm") {
                                                        id = "stage-$index"
                                                        div("w-2 h-2 rounded-full bg-zinc-700") {
                                                            id = "stage-dot-$index"
                                                        }
                                                        span("text-slate-400") {
                                                            id = "stage-text-$index"; +stage
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        div("bg-zinc-800/50 rounded-xl p-4 border border-zinc-700/50 text-sm text-slate-400 select-text") {
                                            id = "status-log"
                                            +"Awaiting stream from remote..."
                                        }

                                        div("hidden bg-zinc-900/50 border border-zinc-800 rounded-xl p-4 space-y-3 select-none") {
                                            id = "sync-summary"
                                            div("flex items-center gap-2 mb-1") {
                                                unsafe { +"""<svg id="summary-icon" xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>""" }
                                                span("text-xs font-bold text-slate-300 uppercase tracking-widest") { +"Sync Summary" }
                                            }
                                            div("grid grid-cols-2 gap-2 select-text") {
                                                id = "summary-grid"
                                            }
                                            div("hidden space-y-2 mt-4 border-t border-zinc-800 pt-4") {
                                                id = "failed-items-container"
                                                span("text-[10px] uppercase font-bold text-red-500") { +"Failed Items" }
                                                div("max-h-40 overflow-y-auto space-y-1.5 pr-2 custom-scrollbar text-[10px] text-red-400/80 select-text") {
                                                    id = "failed-items-list"
                                                }
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

                            // Right Column: Stats Card & Idle State
                            div("space-y-8 select-none") {
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
                                        div("bg-zinc-800/30 px-2 py-1 rounded border border-zinc-700/20 truncate select-text") {
                                            id = "info-host"; +"-"
                                        }
                                        div("bg-zinc-800/30 px-2 py-1 rounded border border-zinc-700/20 select-text") {
                                            id = "info-quality"; +"-"
                                        }
                                    }

                                    div("grid grid-cols-2 gap-4") {
                                        listOf(
                                            "Songs" to "remote-songs",
                                            "Albums" to "remote-albums",
                                            "Artists" to "remote-artists",
                                            "Images" to "remote-images"
                                        ).forEach { (label, id) ->
                                            div("bg-zinc-800/50 p-3 rounded-xl border border-zinc-700/30 text-center") {
                                                p("text-xs text-slate-500 uppercase font-bold") { +label }
                                                p("text-xl font-mono text-amber-300 select-text") {
                                                    this.id = id; +"-"
                                                }
                                            }
                                        }
                                    }

                                    // Selection Containers
                                    div("hidden grid grid-cols-1 gap-4 mt-6") {
                                        id = "selection-containers"

                                        div("bg-zinc-800/30 p-4 rounded-xl border border-zinc-700/20 space-y-3") {
                                            div("flex items-center justify-between") {
                                                h3("text-xs font-bold text-slate-500 uppercase tracking-widest") { +"Filter by Playlists" }
                                                label("custom-checkbox") {
                                                    input(type = InputType.checkBox) {
                                                        attributes["onclick"] =
                                                            "toggleAll('playlist-selection', this.checked)"
                                                    }
                                                    span("checkmark")
                                                    span("text-[10px] text-slate-500 font-bold ml-2 uppercase") { +"All" }
                                                }
                                            }
                                            div("max-h-40 overflow-y-auto space-y-1.5 pr-2 custom-scrollbar") {
                                                id = "playlist-selection"
                                            }
                                        }

                                        div("bg-zinc-800/30 p-4 rounded-xl border border-zinc-700/20 space-y-3") {
                                            div("flex items-center justify-between") {
                                                h3("text-xs font-bold text-slate-500 uppercase tracking-widest") { +"Filter by User Playlists" }
                                                label("custom-checkbox") {
                                                    input(type = InputType.checkBox) {
                                                        attributes["onclick"] =
                                                            "toggleAll('user-playlist-selection', this.checked)"
                                                    }
                                                    span("checkmark")
                                                    span("text-[10px] text-slate-500 font-bold ml-2 uppercase") { +"All" }
                                                }
                                            }
                                            div("max-h-40 overflow-y-auto space-y-1.5 pr-2 custom-scrollbar") {
                                                id = "user-playlist-selection"
                                            }
                                        }

                                        div("bg-zinc-800/30 p-4 rounded-xl border border-zinc-700/20 space-y-3") {
                                            h3("text-xs font-bold text-slate-500 uppercase tracking-widest") { +"Filter by Liked Songs (Per User)" }
                                            div("max-h-40 overflow-y-auto space-y-1.5 pr-2 custom-scrollbar") {
                                                id = "user-liked-selection"
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

        cacheOutput(invalidateAt = 14.days) {
            get("/remote-image/{imageId}") {
                val imageId = call.parameters["imageId"]?.toUUIDOrNull() ?: return@get call.respond(
                    HttpStatusCode.BadRequest
                )
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 0
                val config = call.request.queryParameters.toMirrorConfig()

                try {
                    val imageData = remoteMirrorService.getRemoteImageData(config, imageId, size)
                    if (imageData != null) {
                        call.respondBytes(imageData, ContentType.Image.Any)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        e.message ?: "Failed to fetch remote image"
                    )
                }
            }
        }

        authenticate("synara-auth") {
            post("/start") {
                val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@post call.respond(HttpStatusCode.Forbidden)

                val config = call.receiveParameters().toMirrorConfig()
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

                val config = call.receiveParameters().toMirrorConfig()
                try {
                    val stats = remoteMirrorService.getRemoteStats(config)
                    call.respond(stats)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Failed to fetch stats")
                }
            }

            post("/remote-users") {
                val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@post call.respond(HttpStatusCode.Forbidden)

                val config = call.receiveParameters().toMirrorConfig()
                try {
                    val users = remoteMirrorService.getRemoteUsers(config)
                    call.respond(users)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Failed to fetch users")
                }
            }

            post("/remote-playlists") {
                val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@post call.respond(HttpStatusCode.Forbidden)

                val config = call.receiveParameters().toMirrorConfig()
                try {
                    val playlists = remoteMirrorService.getRemotePlaylists(config)
                    call.respond(playlists)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        e.message ?: "Failed to fetch playlists"
                    )
                }
            }

            post("/remote-user-playlists") {
                val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@post call.respond(HttpStatusCode.Forbidden)

                val config = call.receiveParameters().toMirrorConfig()
                try {
                    val playlists = remoteMirrorService.getRemoteUserPlaylists(config)
                    call.respond(playlists)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        e.message ?: "Failed to fetch user playlists"
                    )
                }
            }

            post("/remote-instances") {
                val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@post call.respond(HttpStatusCode.Forbidden)

                val config = call.receiveParameters().toMirrorConfig()
                try {
                    val instances = remoteMirrorService.getProxyInstances(config)
                    call.respond(instances)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        e.message ?: "Failed to fetch instances"
                    )
                }
            }

            get("/local-users") {
                val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@get call.respond(HttpStatusCode.Forbidden)

                val userService by inject<UserService>()
                try {
                    val users = userService.queryUser().map { it.copy(passwordHash = "") }
                    call.respond(users)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        e.message ?: "Failed to fetch local users"
                    )
                }
            }

            sse("/progress") {
                val user = call.getUser() ?: return@sse call.respond(HttpStatusCode.Unauthorized)
                if (!user.isAdmin) return@sse call.respond(HttpStatusCode.Forbidden)

                remoteMirrorService.getActiveMirrorProgress().conflate().collect { progress ->
                    send(ServerSentEvent(data = ApplicationScope.json.encodeToString(progress)))
                }
            }
        }
    }
}
