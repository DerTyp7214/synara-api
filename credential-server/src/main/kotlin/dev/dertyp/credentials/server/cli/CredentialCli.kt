package dev.dertyp.credentials.server.cli

import dev.dertyp.credentials.*
import dev.dertyp.credentials.server.CredentialServerDeps
import dev.dertyp.credentials.server.broker.CredentialException
import io.ktor.server.config.ConfigLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.encoding.Base64

class CliUsageException(message: String) : RuntimeException(message)

class CredentialCli(
    private val deps: CredentialServerDeps,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
) {
    fun run(args: List<String>): Int = runBlocking {
        try {
            val parsed = CliArgs.parse(args)
            when (parsed.positional.firstOrNull()) {
                "clients" -> clients(parsed.shift())
                "credentials" -> credentials(parsed.shift())
                "keys" -> keys(parsed.shift())
                "help", null -> {
                    out.println(USAGE)
                    0
                }

                else -> throw CliUsageException("Unknown command ${parsed.positional.first()}")
            }
        } catch (e: CliUsageException) {
            err.println(e.message)
            err.println(USAGE)
            2
        } catch (e: CredentialException) {
            err.println("${e.code}: ${e.message}")
            1
        }
    }

    private fun clients(args: CliArgs): Int {
        val store = deps.store
        when (args.command()) {
            "list" -> store.listClients().forEach { printClient(it) }
            "create" -> {
                val name = args.arg(1, "name")
                val grants = args.values("grant").map { parseGrant(it) }
                printCreated(store.createClient(name, grants))
            }

            "rotate" -> printCreated(store.rotateSecret(args.arg(1, "clientId")))
            "enable" -> printClient(store.updateClient(args.arg(1, "clientId"), UpdateClientRequest(enabled = true)))
            "disable" -> printClient(store.updateClient(args.arg(1, "clientId"), UpdateClientRequest(enabled = false)))
            "delete" -> {
                val ref = args.arg(1, "clientId")
                if (!store.deleteClient(ref)) throw CredentialException(
                    CredentialErrorCode.NOT_FOUND,
                    "Client $ref does not exist"
                )
                out.println("Deleted client $ref")
            }

            "grant" -> printClient(store.grant(args.arg(1, "clientId"), args.arg(2, "name"), args.flag("write-back")))
            "ungrant" -> printClient(store.ungrant(args.arg(1, "clientId"), args.arg(2, "name")))
            "revoke-tokens" -> printClient(store.revokeTokens(args.arg(1, "clientId")))
            else -> throw CliUsageException("Unknown clients command ${args.positional.firstOrNull()}")
        }
        return 0
    }

    private suspend fun credentials(args: CliArgs): Int {
        when (args.command()) {
            "list" -> deps.store.listCredentials().forEach { printCredential(it) }
            "set-api-key" -> upsert(args, CredentialKind.API_KEY, CredentialInput.ApiKeyInput(args.arg(2, "key")))
            "set-key-pair" -> upsert(
                args,
                CredentialKind.API_KEY_PAIR,
                CredentialInput.ApiKeyPairInput(args.arg(2, "key"), args.arg(3, "secret")),
            )

            "set-oauth" -> upsert(args, CredentialKind.OAUTH_CLIENT_CREDENTIALS, oauthInput(args))
            "set-apple" -> upsert(
                args,
                CredentialKind.APPLE_DEVELOPER_KEY,
                CredentialInput.AppleDeveloperKeyInput(
                    teamId = args.required("team-id"),
                    keyId = args.required("key-id"),
                    p8Pem = Files.readString(Path.of(args.required("p8"))),
                ),
            )

            "import-file" -> {
                val roles = args.values("role")
                val files = args.values("file")
                if (roles.isEmpty() || roles.size != files.size) {
                    throw CliUsageException("import-file needs one --file per --role")
                }
                val content = roles.zip(files).map { (role, file) ->
                    CredentialFile(role, Base64.encode(Files.readAllBytes(Path.of(file))))
                }
                upsert(args, CredentialKind.FILE, CredentialInput.FileInput(content))
            }

            "import-tiddl" -> upsert(
                args,
                CredentialKind.TIDAL_DEVICE_SESSION,
                CredentialInput.TidalSessionInput(
                    format = TidalSessionFormat.TIDDL,
                    clientId = args.optional("client-id").orEmpty(),
                    clientSecret = args.optional("client-secret").orEmpty(),
                    authFileContent = Files.readString(Path.of(args.required("auth-file"))),
                ),
            )

            "tidal-login" -> return tidalLogin(args)
            "test" -> {
                val name = args.arg(1, "name")
                if (deps.store.kind(name) == null) {
                    throw CredentialException(CredentialErrorCode.NOT_FOUND, "Credential $name does not exist")
                }
                val result = deps.resolver.test(name)
                out.println(
                    listOfNotNull(
                        if (result.ok) "OK" else "FAILED",
                        result.expiresAt?.let { "expires ${Instant.ofEpochMilli(it)}" },
                        result.message,
                    ).joinToString("  "),
                )
                return if (result.ok) 0 else 1
            }

            "delete" -> {
                val name = args.arg(1, "name")
                if (!deps.admin.delete(name)) throw CredentialException(
                    CredentialErrorCode.NOT_FOUND,
                    "Credential $name does not exist"
                )
                out.println("Deleted credential $name")
            }

            else -> throw CliUsageException("Unknown credentials command ${args.positional.firstOrNull()}")
        }
        return 0
    }

    private fun keys(args: CliArgs): Int {
        when (args.command()) {
            "rotate" -> out.println("Active signing key ${deps.tokenIssuer.rotate()}")
            else -> throw CliUsageException("Unknown keys command ${args.positional.firstOrNull()}")
        }
        return 0
    }

    private fun upsert(args: CliArgs, kind: CredentialKind, input: CredentialInput) {
        val summary = deps.admin.upsert(
            args.arg(1, "name"),
            UpsertCredentialRequest(kind = kind, description = args.optional("description"), input = input),
        )
        printCredential(summary)
    }

    private fun oauthInput(args: CliArgs): CredentialInput.OAuthClientCredentialsInput {
        val presetName = args.optional("preset")
        val preset = presetName?.let { wanted ->
            deps.resolver.presets()
                .firstOrNull { it.name == wanted && it.kind == CredentialKind.OAUTH_CLIENT_CREDENTIALS }
                ?: throw CliUsageException("Unknown OAuth preset $wanted")
        }
        val tokenUrl = args.optional("token-url") ?: preset?.tokenUrl
            ?: throw CliUsageException("set-oauth needs --preset or --token-url")
        val authStyle = args.optional("auth-style")?.let { style ->
            OAuthAuthStyle.entries.firstOrNull { it.name.equals(style, ignoreCase = true) }
                ?: throw CliUsageException("Unknown auth style $style")
        } ?: preset?.authStyle ?: OAuthAuthStyle.BASIC
        return CredentialInput.OAuthClientCredentialsInput(
            clientId = args.required("client-id"),
            clientSecret = args.required("client-secret"),
            tokenUrl = tokenUrl,
            authStyle = authStyle,
            scope = args.optional("scope"),
        )
    }

    private suspend fun tidalLogin(args: CliArgs): Int {
        val name = args.arg(1, "name")
        val format = args.optional("format")?.let { value ->
            TidalSessionFormat.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw CliUsageException("Unknown format $value")
        } ?: TidalSessionFormat.TIDDL
        val session = deps.admin.startTidalLogin(
            name,
            TidalLoginStart(format, args.optional("client-id"), args.optional("client-secret")),
        )
        out.println("Open ${session.verificationUriComplete ?: session.verificationUri} and enter the code ${session.userCode}")
        out.println("The code expires at ${Instant.ofEpochMilli(session.expiresAt)}")
        val events = deps.tidalLogins.events(session.loginId)
            ?: throw CredentialException(CredentialErrorCode.NOT_FOUND, "Tidal login ${session.loginId} vanished")
        val final = events.first { it.state != TidalLoginState.PENDING }
        out.println(listOfNotNull(final.state.name, final.message).joinToString(": "))
        return if (final.state == TidalLoginState.COMPLETED) 0 else 1
    }

    private fun parseGrant(value: String): GrantSpec =
        if (value.endsWith(":w")) GrantSpec(value.removeSuffix(":w"), writeBack = true) else GrantSpec(value)

    private fun printCreated(created: CreatedClient) {
        printClient(created.client)
        out.println("clientId:     ${created.client.clientId}")
        out.println("clientSecret: ${created.clientSecret}")
        out.println("The secret is shown only once")
    }

    private fun printClient(client: ClientSummary) {
        val grants = client.grants.joinToString(",") { if (it.writeBack) "${it.name}:w" else it.name }.ifEmpty { "-" }
        out.println(
            listOf(
                client.clientId,
                client.name,
                if (client.enabled) "enabled" else "disabled",
                "v${client.tokenVersion}",
                grants
            )
                .joinToString("\t"),
        )
    }

    private fun printCredential(credential: CredentialSummary) {
        out.println(
            listOf(
                credential.name,
                credential.kind.name,
                credential.status.name,
                credential.expiresAt?.let { Instant.ofEpochMilli(it).toString() } ?: "-",
                credential.grantedTo.joinToString(",").ifEmpty { "-" },
            ).joinToString("\t"),
        )
    }

    companion object {
        private val COMMANDS = setOf("clients", "credentials", "keys", "help")

        fun isCommand(args: Array<String>): Boolean = args.firstOrNull() in COMMANDS

        fun main(args: Array<String>): Int {
            val config = ConfigLoader.load("application.yaml")
            return CredentialServerDeps.create(config).use { deps -> CredentialCli(deps).run(args.toList()) }
        }

        val USAGE = """
            Usage:
              clients list
              clients create <name> [--grant <credential>[:w]]...
              clients rotate|enable|disable|delete|revoke-tokens <clientId>
              clients grant <clientId> <credential> [--write-back]
              clients ungrant <clientId> <credential>
              credentials list
              credentials set-api-key <name> <key>
              credentials set-key-pair <name> <key> <secret>
              credentials set-oauth <name> --preset <preset> --client-id <id> --client-secret <secret>
              credentials set-apple <name> --team-id <id> --key-id <id> --p8 <path>
              credentials import-file <name> --role <role> --file <path> [--role <role> --file <path>]...
              credentials import-tiddl <name> --auth-file <path> [--client-id <id> --client-secret <secret>]
              credentials tidal-login <name> --format tiddl|tdn [--client-id <id> --client-secret <secret>]
              credentials test|delete <name>
              keys rotate
        """.trimIndent()
    }
}

class CliArgs(
    val positional: List<String>,
    private val options: Map<String, List<String>>,
    private val flags: Set<String>,
) {
    fun shift() = CliArgs(positional.drop(1), options, flags)

    fun command(): String = positional.firstOrNull() ?: throw CliUsageException("Missing command")

    fun arg(index: Int, name: String): String =
        positional.getOrNull(index) ?: throw CliUsageException("Missing <$name>")

    fun flag(name: String) = name in flags

    fun values(name: String): List<String> = options[name].orEmpty()

    fun optional(name: String): String? = options[name]?.lastOrNull()

    fun required(name: String): String = optional(name) ?: throw CliUsageException("Missing --$name")

    companion object {
        private val FLAGS = setOf("write-back")

        fun parse(args: List<String>): CliArgs {
            val positional = mutableListOf<String>()
            val options = mutableMapOf<String, MutableList<String>>()
            val flags = mutableSetOf<String>()
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (arg.startsWith("--")) {
                    val body = arg.removePrefix("--")
                    val name = body.substringBefore('=')
                    when {
                        body.contains('=') -> options.getOrPut(name) { mutableListOf() }.add(body.substringAfter('='))
                        name in FLAGS -> flags.add(name)
                        else -> {
                            val value =
                                args.getOrNull(index + 1) ?: throw CliUsageException("Missing value for --$name")
                            options.getOrPut(name) { mutableListOf() }.add(value)
                            index++
                        }
                    }
                } else {
                    positional.add(arg)
                }
                index++
            }
            return CliArgs(positional, options, flags)
        }
    }
}
