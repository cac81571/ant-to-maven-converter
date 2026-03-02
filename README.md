# Ant to Maven Converter

A GUI tool that scans legacy Ant project folders (e.g. `lib`), computes JAR SHA-1 hashes, looks them up on Maven Central, and **generates pom.xml** automatically.

**日本語:** [README_ja.md](README_ja.md)

---

## Features

- **GUI** … Swing window for project path selection, execution, and log viewing
- **JAR discovery** … Recursively scans the chosen directory and collects `.jar` files
- **Maven Central lookup** … Computes SHA-1 for each JAR and queries [Maven Central Search API](https://search.maven.org/) for groupId / artifactId / version
- **Config file** … Groovy ConfigSlurper format for flexible “exclude”, “add”, and “replace” rules
- **Latest version** … Optional upgrade of detected artifacts to their latest versions (uses Maven Central **maven-metadata.xml**). Pre-release versions (alpha, beta, rc, SNAPSHOT, etc.) are excluded, and the major version is not changed. Configurable via `preReleaseVersionPatterns`.
- **Update pom.xml dependencies** … Button to update dependency versions in an existing `pom.xml` to the latest from Maven Central (preserves comments via DOM; respects `excludeFromVersionUpgrade`)
- **Local JARs** … JARs not found on Maven Central are written to `pom.xml` with `system` scope
- **i18n** … UI language switch: 日本語 / English (dropdown)
- **JAR path exclusion** … Glob patterns in config (e.g. `**/test/**`) to exclude paths from scanning
- **CSV export/import** … Export dependencies from `pom.xml` to CSV, or import from CSV into `pom.xml`
- **History in text files** … Project folder and config file paths are stored under `~/.ant-to-maven-converter/` as `project-history.txt` and `config-history.txt` (one path per line, UTF-8). You can clear project history via the **Clear history** button.

## Requirements

- **Java 17** or later
- **Maven 3.x** (for building)

## Build

```bash
mvn clean package
```

The runnable JAR is produced at `target/ant-to-maven-converter-1.0.0.jar` (dependencies shaded).

## Run

### From JAR

```bash
java -jar target/ant-to-maven-converter-1.0.0.jar
```

### From IDE

Run the `main` method of the `AntToMavenTool` class.

## Usage

1. On startup the **“Ant to Maven POM Generator”** window opens.
2. Set **Project folder** to the root of the Ant project to convert (parent of directories that contain JARs, e.g. `lib`).
3. Optionally choose a **config file** path. Default: when run from a JAR, the config is read from the **same folder as the JAR**; when run from an IDE, from the **classpath root** (e.g. `src/main/resources/`). Fallback: `~/.ant-to-maven-converter/ant-to-maven-default.groovy`.
4. Check **“Replace dependency versions with latest”** to upgrade detected dependencies to the latest **stable** version within the same major version (alpha/beta/rc/SNAPSHOT are excluded).
5. Click **“Generate POM”** to scan the directory and generate `pom.xml`.
6. If `pom.xml` already exists, you can choose Overwrite, Save as, or Cancel.

The generated `pom.xml` is written under the project root (the path you specified).

### Updating an existing pom.xml

If the project already has a `pom.xml`, you can update its dependency versions to the latest from Maven Central without regenerating the whole file:

1. Set **Project folder** to the project that contains `pom.xml`.
2. Click **“Update pom.xml dependencies”** (to the right of the Stop button).
3. The tool reads the current dependencies, fetches the latest versions from Maven Central, and updates only the `<version>` elements. XML comments and formatting are preserved (DOM-based). Dependencies listed in `excludeFromVersionUpgrade` in the config file are not updated.

## Config file

Configuration is in **Groovy ConfigSlurper** format.

- **Default location**
  - **JAR run:** same folder as the JAR (`ant-to-maven-default.groovy`; created from bundled template if missing)
  - **IDE run:** classpath root (e.g. `src/main/resources/ant-to-maven-default.groovy`)
  - **Fallback:** `~/.ant-to-maven-converter/` (Windows: `%USERPROFILE%\.ant-to-maven-converter\`)

- **History storage**  
  Project folder and config file path histories are stored under `~/.ant-to-maven-converter/` as plain text:
  - `project-history.txt` — one project path per line (UTF-8)
  - `config-history.txt` — one config path per line (UTF-8)

### Main options

| Option | Description |
|--------|-------------|
| `excludeDependencies` | List of `groupId:artifactId` to omit from the generated pom |
| `excludeJarPaths` | Glob patterns for JAR paths to exclude from scanning (e.g. `**/test/**`) |
| `excludeFromVersionUpgrade` | Dependencies to skip when upgrading to latest (string `groupId:artifactId` or map with `key` and optional `version`). Used by “Replace with latest” and “Update pom.xml dependencies”. |
| `addDependencies` | Dependencies to add at the top of the result |
| `replaceDependencies` | **Map**: key = `groupId:artifactId` (string), value = `[ to: singleDep ]` or `[ to: [ dep1, dep2, ... ] ]` for 1:1 or 1:N replacement. Each dep is a Map (`groupId`, `artifactId`, `version`, `scope`…) or string `"g:a:v"`. |
| `preReleaseVersionPatterns` | List of substrings that mark a version as pre-release (excluded when upgrading). Default: `alpha`, `beta`, `-rc`, `.rc`, `snapshot`, `milestone`, `preview`. Case-insensitive. |
| `pomProjectTemplate` | Template for the generated pom; `{{DEPENDENCIES}}` is replaced by the dependencies block |

See the sample in the repo: `src/main/resources/ant-to-maven-default.groovy`.

For method-level documentation, see [METHODS.md](docs/METHODS.md).

## Project layout

```
ant-to-maven-converter/
├── pom.xml
├── README.md
├── README_ja.md
├── docs/
│   └── METHODS.md          # Method-level documentation (AntToMavenTool)
└── src/
    └── main/
        ├── groovy/
        │   └── AntToMavenConverter.groovy   # Main class: AntToMavenTool
        └── resources/
            ├── ant-to-maven-default.groovy # Config sample
            ├── messages_ja.properties      # Japanese UI messages
            └── messages_en.properties      # English UI messages
```

## License

The project license is defined at the repository root, if applicable.
