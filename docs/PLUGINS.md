# Synara Custom Plugins

Synara supports an extensible plugin architecture that allows you to add custom importers, indexers, and other services.

## Plugin Structure

A Synara plugin is a Java/Kotlin project that implements the `ISynaraPlugin` interface from the `plugin-api` module.

### `ISynaraPlugin` Interface

```kotlin
interface ISynaraPlugin {
    val id: String
    val name: String
    val apiVersion: Int get() = 1

    fun init(context: PluginContext)

    fun getImporters(): List<IImporter> = emptyList()
    fun getIndexers(): List<IPluginIndexer> = emptyList()
}
```

### Components

- **`IImporter`**: Responsible for handling specific URLs or content types. It defines logic for importing and metadata enrichment.
- **`IPluginIndexer`**: Responsible for scanning the filesystem and inserting media records into the Synara database.
- **`PluginContext`**: Provides access to core Synara services like the logger, storage, and database libraries.

## Creating a Plugin

1.  **Depend on `plugin-api`**: Add the Synara `plugin-api` module as a dependency in your project.
2.  **Implement `ISynaraPlugin`**: Create a class that implements `ISynaraPlugin`.
3.  **Define Services**: Implement `IImporter` or `IPluginIndexer` if your plugin provides these capabilities.
4.  **Register via `ServiceLoader`**:
    -   Create a file named `dev.dertyp.plugins.ISynaraPlugin` in `src/main/resources/META-INF/services/`.
    -   Add the fully qualified name of your implementation class to this file.

## Installing a Plugin

1.  **Build the JAR**: Package your plugin as a shadow/fat JAR (including all non-provided dependencies).
2.  **Deploy**: Place the resulting `.jar` file into the `plugins/` directory in the Synara root.
3.  **Restart**: Restart the Synara server. You should see "Loaded plugin: [Your Plugin Name]" in the logs.

## Versioning

Plugins specify an `apiVersion`. Synara will only load plugins with an `apiVersion` less than or equal to the server's current supported version. This ensures backward compatibility as the plugin API evolves.

Current Supported API Version: **1**
